import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import exec from 'k6/execution';

const BASE_URL = __ENV.BASE_URL;
if (!BASE_URL) {
    throw new Error(
        'BASE_URL 환경변수가 필요합니다. 예: k6 run -e BASE_URL=http://localhost:8080 k6/reservation-sustained-load.js'
    );
}

const USER_COUNT = __ENV.USER_COUNT ? parseInt(__ENV.USER_COUNT, 10) : 20;
if (!Number.isInteger(USER_COUNT) || USER_COUNT < 1) {
    throw new Error(`USER_COUNT는 1 이상의 정수여야 합니다. 입력값: "${__ENV.USER_COUNT}"`);
}

const TEST_DURATION = __ENV.TEST_DURATION || '30s';

const TARGET_RPS = __ENV.TARGET_RPS ? parseInt(__ENV.TARGET_RPS, 10) : 5;
if (!Number.isInteger(TARGET_RPS) || TARGET_RPS < 1) {
    throw new Error(`TARGET_RPS는 1 이상의 정수여야 합니다. 입력값: "${__ENV.TARGET_RPS}"`);
}

function parseDurationToSeconds(durationStr) {
    const match = /^(\d+)(ms|s|m|h)$/.exec(durationStr);
    if (!match) {
        throw new Error(`TEST_DURATION 형식이 올바르지 않습니다: "${durationStr}" (예: 30s, 2m, 1h)`);
    }
    const value = Number(match[1]);
    const secondsPerUnit = { ms: 0.001, s: 1, m: 60, h: 3600 };
    return value * secondsPerUnit[match[2]];
}

const TOTAL_SECONDS = parseDurationToSeconds(TEST_DURATION);
if (TOTAL_SECONDS < 3) {
    throw new Error(`TEST_DURATION이 너무 짧습니다(최소 3초 이상 필요): "${TEST_DURATION}"`);
}

const RAMP_UP_SECONDS = Math.max(1, Math.round(TOTAL_SECONDS * 0.3));
const RAMP_DOWN_SECONDS = Math.max(1, Math.round(TOTAL_SECONDS * 0.3));
const STEADY_SECONDS = Math.max(1, TOTAL_SECONDS - RAMP_UP_SECONDS - RAMP_DOWN_SECONDS);
const START_RATE = 1;
const RAMP_DOWN_END_RATE = 0;

const TEST_PASSWORD = 'K6-Load-Test-1234!';

const RUN_ID = Date.now();

http.setResponseCallback(http.expectedStatuses(200, 201, 409));

const reservationSuccess = new Counter('reservation_success');
const reservationConflict = new Counter('reservation_conflict');
const reservationLockConflict = new Counter('reservation_lock_conflict');
const reservationTimeConflict = new Counter('reservation_time_conflict');
const reservationUnexpected = new Counter('reservation_unexpected');
const reservationDataExhausted = new Counter('reservation_data_exhausted');
const reservationDuration = new Trend('reservation_duration', true);

const LOCK_CONFLICT_MESSAGE = '해당 시간대의 예약 요청이 처리 중입니다.';
const TIME_CONFLICT_MESSAGE = '이미 예약된 시간과 겹칩니다.';

const ASSUMED_AVG_RESPONSE_SECONDS = 0.3;
const PRE_ALLOCATED_VUS = Math.max(10, Math.ceil(TARGET_RPS * ASSUMED_AVG_RESPONSE_SECONDS * 4));
const MAX_VUS = Math.max(PRE_ALLOCATED_VUS * 2, Math.ceil(TARGET_RPS * ASSUMED_AVG_RESPONSE_SECONDS * 8));

const estimatedRampUpRequests = ((START_RATE + TARGET_RPS) / 2) * RAMP_UP_SECONDS;
const estimatedSteadyRequests = TARGET_RPS * STEADY_SECONDS;
const estimatedRampDownRequests = ((TARGET_RPS + RAMP_DOWN_END_RATE) / 2) * RAMP_DOWN_SECONDS;
const ESTIMATED_TOTAL_REQUESTS = Math.ceil(
    estimatedRampUpRequests + estimatedSteadyRequests + estimatedRampDownRequests
);
const POOL_SIZE = Math.ceil(ESTIMATED_TOTAL_REQUESTS * 1.3) + 10;

export const options = {
    scenarios: {
        sustained_reservation_load: {
            executor: 'ramping-arrival-rate',
            timeUnit: '1s',
            startRate: START_RATE,
            preAllocatedVUs: PRE_ALLOCATED_VUS,
            maxVUs: MAX_VUS,
            stages: [
                { target: TARGET_RPS, duration: `${RAMP_UP_SECONDS}s` },
                { target: TARGET_RPS, duration: `${STEADY_SECONDS}s` },
                { target: RAMP_DOWN_END_RATE, duration: `${RAMP_DOWN_SECONDS}s` },
            ],
        },
    },
    setupTimeout: '180s',
    summaryTrendStats: ['avg', 'p(95)', 'p(99)', 'max'],
    thresholds: {
        http_req_failed: ['rate<0.01'],
        reservation_unexpected: ['count==0'],
        http_req_duration: ['p(95)<1000'],
        reservation_data_exhausted: ['count==0'],
    },
};

function jsonHeaders(token) {
    const headers = { 'Content-Type': 'application/json' };
    if (token) {
        headers['Authorization'] = `Bearer ${token}`;
    }
    return headers;
}

function pad(n) {
    return String(n).padStart(2, '0');
}

function formatDate(date) {
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

function parseLocalDateTime(localDateTimeStr) {
    return new Date(localDateTimeStr);
}

function pickNonOverlappingStartAts(availableSlots, duration, maxCount) {
    const picked = [];
    let cursorMs = 0;
    for (const slot of availableSlots) {
        if (picked.length >= maxCount) {
            break;
        }
        const slotStartMs = parseLocalDateTime(slot.startAt).getTime();
        if (slotStartMs < cursorMs) {
            continue;
        }
        picked.push(slot.startAt);
        cursorMs = slotStartMs + duration * 60 * 1000;
    }
    return picked;
}

function collectNonOverlappingStartAts(token, resourceId, duration, maxNeeded) {
    const MAX_LOOKAHEAD_DAYS = 60;
    const today = new Date();
    const results = [];

    for (let dayOffset = 1; dayOffset <= MAX_LOOKAHEAD_DAYS && results.length < maxNeeded; dayOffset++) {
        const candidateDate = new Date(today);
        candidateDate.setDate(candidateDate.getDate() + dayOffset);
        const dateStr = formatDate(candidateDate);

        const availabilityRes = http.get(
            `${BASE_URL}/api/resources/${resourceId}/availability?date=${dateStr}`,
            { headers: jsonHeaders(token) }
        );
        if (availabilityRes.status !== 200) {
            throw new Error(
                `[setup] Resource(id=${resourceId}) 가용시간 조회 실패 (date=${dateStr}, status=${availabilityRes.status}): ${availabilityRes.body}`
            );
        }

        const slots = (availabilityRes.json('slots') || []).filter((slot) => slot.available === true);
        const picked = pickNonOverlappingStartAts(slots, duration, maxNeeded - results.length);
        results.push(...picked);
    }

    return results;
}

function assertNoOverlap(resourceId, startAts, duration) {
    const sorted = startAts
        .map((startAt) => {
            const startMs = parseLocalDateTime(startAt).getTime();
            return { startAt, startMs, endMs: startMs + duration * 60 * 1000 };
        })
        .sort((a, b) => a.startMs - b.startMs);

    for (let i = 1; i < sorted.length; i++) {
        const previous = sorted[i - 1];
        const current = sorted[i];
        if (previous.endMs > current.startMs) {
            throw new Error(
                `[setup] Resource(id=${resourceId})의 예약 계획이 서로 겹칩니다: ` +
                    `이전 예약 [${previous.startAt} ~ endMs=${previous.endMs}] 와 ` +
                    `다음 예약 [${current.startAt}]가 겹칩니다. non-overlap 보장 로직을 확인하세요.`
            );
        }
    }
}

export function setup() {
    const tokens = [];

    for (let i = 0; i < USER_COUNT; i++) {
        const email = `k6-${RUN_ID}-${i}@test.com`;

        const signupRes = http.post(
            `${BASE_URL}/api/users/signup`,
            JSON.stringify({ email, password: TEST_PASSWORD, name: `k6-user-${i}` }),
            { headers: jsonHeaders() }
        );
        if (signupRes.status !== 201) {
            throw new Error(
                `[setup] 회원가입 실패 (index=${i}, email=${email}, status=${signupRes.status}): ${signupRes.body}`
            );
        }

        const loginRes = http.post(
            `${BASE_URL}/api/auth/login`,
            JSON.stringify({ email, password: TEST_PASSWORD }),
            { headers: jsonHeaders() }
        );
        if (loginRes.status !== 200) {
            throw new Error(
                `[setup] 로그인 실패 (index=${i}, email=${email}, status=${loginRes.status}): ${loginRes.body}`
            );
        }

        const accessToken = loginRes.json('accessToken');
        if (!accessToken) {
            throw new Error(`[setup] 로그인 응답에 accessToken이 없습니다 (index=${i}): ${loginRes.body}`);
        }
        tokens.push(accessToken);
    }

    const resourcesRes = http.get(`${BASE_URL}/api/resources?page=0&size=100`, {
        headers: jsonHeaders(tokens[0]),
    });
    if (resourcesRes.status !== 200) {
        throw new Error(`[setup] Resource 목록 조회 실패 (status=${resourcesRes.status}): ${resourcesRes.body}`);
    }

    const content = resourcesRes.json('content');
    if (!content || content.length === 0) {
        throw new Error('[setup] 조회된 Resource가 없습니다. 테스트를 진행할 수 없습니다.');
    }

    const activeResources = content.filter((r) => r.status === 'ACTIVE');
    if (activeResources.length === 0) {
        throw new Error('[setup] ACTIVE 상태인 Resource가 없습니다. 테스트를 진행할 수 없습니다.');
    }

    const usableResources = activeResources.filter((r) => r.reservationPolicy && r.reservationPolicy.minDuration);
    if (usableResources.length === 0) {
        throw new Error('[setup] 예약 정책(minDuration)이 설정된 ACTIVE Resource가 없습니다.');
    }
    const skippedCount = activeResources.length - usableResources.length;
    if (skippedCount > 0) {
        console.warn(`[setup] 예약 정책이 없는 ACTIVE Resource ${skippedCount}개를 건너뜁니다.`);
    }

    const slotsPerResource = Math.ceil(POOL_SIZE / usableResources.length);
    const perResourcePlans = usableResources.map((resource) => {
        const duration = resource.reservationPolicy.minDuration;
        const startAts = collectNonOverlappingStartAts(tokens[0], resource.id, duration, slotsPerResource);
        return { resourceId: resource.id, duration, startAts };
    });

    for (const plan of perResourcePlans) {
        assertNoOverlap(plan.resourceId, plan.startAts, plan.duration);
    }

    const assignments = [];
    const cursors = perResourcePlans.map(() => 0);
    const resourceUsage = {};
    let anyRemaining = true;
    while (assignments.length < POOL_SIZE && anyRemaining) {
        anyRemaining = false;
        for (let r = 0; r < perResourcePlans.length; r++) {
            if (assignments.length >= POOL_SIZE) {
                break;
            }
            const plan = perResourcePlans[r];
            if (cursors[r] < plan.startAts.length) {
                assignments.push({
                    resourceId: plan.resourceId,
                    startAt: plan.startAts[cursors[r]],
                    duration: plan.duration,
                });
                cursors[r]++;
                resourceUsage[plan.resourceId] = (resourceUsage[plan.resourceId] || 0) + 1;
                anyRemaining = true;
            }
        }
    }

    console.log(
        `[setup] 완료 - USER_COUNT=${USER_COUNT}, TARGET_RPS=${TARGET_RPS}, TEST_DURATION=${TEST_DURATION} ` +
            `(ramp-up ${RAMP_UP_SECONDS}s / steady ${STEADY_SECONDS}s / ramp-down ${RAMP_DOWN_SECONDS}s), ` +
            `사용 Resource 수=${usableResources.length}, VU 풀=${PRE_ALLOCATED_VUS}~${MAX_VUS}, ` +
            `확보된 슬롯 총합=${assignments.length}/${POOL_SIZE} (예상 요청 수 ${ESTIMATED_TOTAL_REQUESTS}), ` +
            `Resource별 배정 slot 수=${JSON.stringify(resourceUsage)}`
    );

    if (assignments.length < ESTIMATED_TOTAL_REQUESTS) {
        console.warn(
            `[setup] 확보된 예약 slot(${assignments.length})이 예상 요청 수(${ESTIMATED_TOTAL_REQUESTS})보다 적습니다. ` +
                'Resource 예약 가능 여력이 부족하면 테스트 후반부 일부 iteration이 reservation_data_exhausted로 집계됩니다.'
        );
    }

    return {
        tokens,
        assignments,
    };
}

export default function (data) {
    const index = exec.scenario.iterationInTest;
    const assignment = data.assignments[index];

    if (!assignment) {
        reservationDataExhausted.add(1);
        console.error(
            `[data-exhausted] iterationInTest=${index}가 사전 확보한 assignments(${data.assignments.length}개)를 초과했습니다. ` +
                'POOL_SIZE 추정치를 늘리거나 TARGET_RPS/TEST_DURATION을 조정하세요.'
        );
        return;
    }

    const token = data.tokens[index % data.tokens.length];

    const payload = JSON.stringify({
        resourceId: assignment.resourceId,
        startAt: assignment.startAt,
        duration: assignment.duration,
    });

    const res = http.post(`${BASE_URL}/api/reservations`, payload, {
        headers: jsonHeaders(token),
        tags: { name: 'ReserveSustainedLoad' },
    });

    reservationDuration.add(res.timings.duration);

    check(res, {
        '응답 status가 201 또는 409': (r) => r.status === 201 || r.status === 409,
    });

    if (res.status === 201) {
        reservationSuccess.add(1);
    } else if (res.status === 409) {
        reservationConflict.add(1);

        const message = res.json('message');
        if (message === LOCK_CONFLICT_MESSAGE) {
            reservationLockConflict.add(1);
        } else if (message === TIME_CONFLICT_MESSAGE) {
            reservationTimeConflict.add(1);
        } else {
            console.warn(`[409-미분류] iterationInTest=${index} message="${message}"`);
        }
    } else {
        reservationUnexpected.add(1);
        console.error(`[unexpected] iterationInTest=${index} status=${res.status} body=${res.body}`);
    }
}

export function handleSummary(data) {
    const countOf = (name) => (data.metrics[name] ? data.metrics[name].values.count : 0);

    const success = countOf('reservation_success');
    const conflict = countOf('reservation_conflict');
    const lockConflict = countOf('reservation_lock_conflict');
    const timeConflict = countOf('reservation_time_conflict');
    const unclassifiedConflict = conflict - lockConflict - timeConflict;
    const unexpected = countOf('reservation_unexpected');
    const dataExhausted = countOf('reservation_data_exhausted');
    const totalRequests = success + conflict + unexpected;

    const iterationCount = data.metrics.iterations ? data.metrics.iterations.values.count : 0;
    const iterationsMatchRequests = iterationCount === totalRequests;

    const requestsPerSecond = totalRequests / TOTAL_SECONDS;

    const durationMetric = data.metrics.reservation_duration;
    const avgDuration = durationMetric ? durationMetric.values.avg.toFixed(2) : 'N/A';
    const p95Duration = durationMetric ? durationMetric.values['p(95)'].toFixed(2) : 'N/A';
    const p99Duration = durationMetric ? durationMetric.values['p(99)'].toFixed(2) : 'N/A';
    const maxDuration = durationMetric ? durationMetric.values.max.toFixed(2) : 'N/A';

    const httpFailedRate = data.metrics.http_req_failed
        ? (data.metrics.http_req_failed.values.rate * 100).toFixed(2)
        : 'N/A';
    const httpFailedRateNum = data.metrics.http_req_failed ? data.metrics.http_req_failed.values.rate : 0;
    const p95Num = durationMetric ? durationMetric.values['p(95)'] : Number.POSITIVE_INFINITY;

    const pass = unexpected === 0 && httpFailedRateNum < 0.01 && p95Num < 1000 && dataExhausted === 0;

    const failReasons = [];
    if (unexpected !== 0) failReasons.push(`unexpected=${unexpected}`);
    if (httpFailedRateNum >= 0.01) failReasons.push(`http_req_failed=${httpFailedRate}%`);
    if (p95Num >= 1000) failReasons.push(`p95=${p95Duration}ms`);
    if (dataExhausted !== 0) failReasons.push(`reservation_data_exhausted=${dataExhausted}`);

    const lines = [
        '',
        '===== 예약 정상 지속 부하 테스트 결과 =====',
        `k6 iteration 수                        : ${iterationCount}`,
        `실제 예약 POST 요청 수                  : ${totalRequests}`,
        `  ㄴ iteration 수 == POST 요청 수 여부   : ${iterationsMatchRequests ? '일치' : '불일치 (데이터 배정 문제 의심)'}`,
        `데이터 할당 실패 (reservation_data_exhausted) : ${dataExhausted}`,
        `201 성공 (reservation_success)          : ${success}`,
        `409 전체 (reservation_conflict)         : ${conflict}`,
        `  ㄴ Redis Lock 충돌 (reservation_lock_conflict)  : ${lockConflict}`,
        `  ㄴ DB 시간 중복 충돌 (reservation_time_conflict) : ${timeConflict}`,
        `  ㄴ 미분류 409 (message 불일치)                   : ${unclassifiedConflict}`,
        `예상 밖 응답 (reservation_unexpected)    : ${unexpected}`,
        `요청 처리율 (requests/sec)               : ${requestsPerSecond.toFixed(2)}`,
        `응답시간 avg / p95 / p99 / max (ms)      : ${avgDuration} / ${p95Duration} / ${p99Duration} / ${maxDuration}`,
        `HTTP 요청 실패율 (http_req_failed)       : ${httpFailedRate}%`,
        `판정: ${pass ? 'PASS - 모든 threshold 충족' : `FAIL - ${failReasons.join(', ')}`}`,
        '===========================================',
        '',
    ];

    return {
        stdout: lines.join('\n'),
        'k6-reservation-sustained-load-summary.json': JSON.stringify(data, null, 2),
    };
}

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import exec from 'k6/execution';

const BASE_URL = __ENV.BASE_URL;
if (!BASE_URL) {
    throw new Error(
        'BASE_URL 환경변수가 필요합니다. 예: k6 run -e BASE_URL=http://localhost:8080 k6/reservation-normal-load.js'
    );
}

const USER_COUNT = __ENV.USER_COUNT ? parseInt(__ENV.USER_COUNT, 10) : 50;
if (!Number.isInteger(USER_COUNT) || USER_COUNT < 1) {
    throw new Error(`USER_COUNT는 1 이상의 정수여야 합니다. 입력값: "${__ENV.USER_COUNT}"`);
}

const TEST_PASSWORD = 'K6-Load-Test-1234!';

const RUN_ID = Date.now();

http.setResponseCallback(http.expectedStatuses(200, 201, 409));

const reservationSuccess = new Counter('reservation_success');
const reservationConflict = new Counter('reservation_conflict');
const reservationUnexpected = new Counter('reservation_unexpected');
const reservationDuration = new Trend('reservation_duration', true);

export const options = {
    scenarios: {
        normal_load_reservation: {
            executor: 'per-vu-iterations',
            vus: USER_COUNT,
            iterations: 1,
            maxDuration: '2m',
        },
    },
    setupTimeout: '180s',
    thresholds: {
        http_req_failed: ['rate<0.01'],
        checks: ['rate==1'],
        reservation_unexpected: ['count==0'],
        reservation_success: [`count==${USER_COUNT}`],
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
    const MAX_LOOKAHEAD_DAYS = 30;
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

    if (activeResources.length < USER_COUNT) {
        throw new Error(
            '[setup] 비경합 정상 부하 테스트에는 USER_COUNT개 이상의 ACTIVE Resource가 필요합니다.\n' +
                `현재 ACTIVE Resource: ${activeResources.length}, USER_COUNT: ${USER_COUNT}`
        );
    }

    const assignments = [];
    const usedResourceIds = [];
    for (const resource of activeResources) {
        if (assignments.length >= USER_COUNT) {
            break;
        }

        const policy = resource.reservationPolicy;
        if (!policy || !policy.minDuration) {
            console.warn(`[setup] Resource(id=${resource.id})에 예약 정책이 없어 건너뜁니다.`);
            continue;
        }

        const duration = policy.minDuration;
        const startAts = collectNonOverlappingStartAts(tokens[0], resource.id, duration, 1);
        if (startAts.length === 0) {
            console.warn(`[setup] Resource(id=${resource.id})에 예약 가능한 slot이 없어 건너뜁니다.`);
            continue;
        }

        assignments.push({ resourceId: resource.id, startAt: startAts[0], duration });
        usedResourceIds.push(resource.id);
    }

    if (assignments.length < USER_COUNT) {
        throw new Error(
            `[setup] resourceId가 겹치지 않는 예약을 ${USER_COUNT}건 확보하지 못했습니다 ` +
                `(확보: ${assignments.length}건, ACTIVE Resource 수: ${activeResources.length}개). ` +
                '일부 Resource에 예약 가능한 slot이 없었습니다. USER_COUNT를 줄이거나 Resource 예약 현황을 확인하세요.'
        );
    }

    console.log(
        `[setup] 완료 - USER_COUNT=${USER_COUNT}, 사용된 Resource 수=${usedResourceIds.length} ` +
            `(VU 1명당 서로 다른 Resource 1개), resourceIds=${JSON.stringify(usedResourceIds)}`
    );

    return {
        tokens,
        assignments,
        fireAt: Date.now() + 3000,
    };
}

export default function (data) {
    const index = (exec.vu.idInTest - 1) % data.tokens.length;
    const token = data.tokens[index];
    const assignment = data.assignments[index];

    const waitMs = data.fireAt - Date.now();
    if (waitMs > 0) {
        sleep(waitMs / 1000);
    }

    const payload = JSON.stringify({
        resourceId: assignment.resourceId,
        startAt: assignment.startAt,
        duration: assignment.duration,
    });

    const res = http.post(`${BASE_URL}/api/reservations`, payload, {
        headers: jsonHeaders(token),
        tags: { name: 'ReserveNormalLoad' },
    });

    reservationDuration.add(res.timings.duration);

    check(res, {
        '응답 status가 201 또는 409': (r) => r.status === 201 || r.status === 409,
    });

    if (res.status === 201) {
        reservationSuccess.add(1);
    } else if (res.status === 409) {
        reservationConflict.add(1);
        console.warn(
            `[예상치 못한 409] VU=${exec.vu.idInTest} resourceId=${assignment.resourceId} ` +
                `startAt=${assignment.startAt} body=${res.body}`
        );
    } else {
        reservationUnexpected.add(1);
        console.error(`[unexpected] VU=${exec.vu.idInTest} status=${res.status} body=${res.body}`);
    }
}

export function handleSummary(data) {
    const countOf = (name) => (data.metrics[name] ? data.metrics[name].values.count : 0);

    const success = countOf('reservation_success');
    const conflict = countOf('reservation_conflict');
    const unexpected = countOf('reservation_unexpected');
    const total = success + conflict + unexpected;

    const durationMetric = data.metrics.reservation_duration;
    const avgDuration = durationMetric ? durationMetric.values.avg.toFixed(2) : 'N/A';
    const p95Duration = durationMetric ? durationMetric.values['p(95)'].toFixed(2) : 'N/A';
    const maxDuration = durationMetric ? durationMetric.values.max.toFixed(2) : 'N/A';

    const httpFailedRate = data.metrics.http_req_failed
        ? (data.metrics.http_req_failed.values.rate * 100).toFixed(2)
        : 'N/A';

    let verdict;
    if (success === USER_COUNT && conflict === 0 && unexpected === 0) {
        verdict = 'PASS - 서로 겹치지 않는 예약 요청이 전원(USER_COUNT) 201로 성공함';
    } else if (conflict > 0) {
        verdict = `FAIL - 정상 트래픽인데 409 충돌이 ${conflict}건 발생함 (예약 데이터 분산 로직 점검 필요)`;
    } else {
        verdict = 'FAIL - 성공 건수가 USER_COUNT와 다르거나 예상 밖 응답이 발생함';
    }

    const lines = [
        '',
        '===== 예약 정상 부하 테스트 결과 =====',
        `총 요청 수                           : ${total}`,
        `201 성공 (reservation_success)       : ${success} / ${USER_COUNT}`,
        `409 충돌 (reservation_conflict)      : ${conflict}`,
        `예상 밖 응답 (reservation_unexpected) : ${unexpected}`,
        `예약 요청 응답시간 avg/p95/max (ms)  : ${avgDuration} / ${p95Duration} / ${maxDuration}`,
        `HTTP 요청 실패율(http_req_failed)    : ${httpFailedRate}%`,
        `판정: ${verdict}`,
        '=======================================',
        '',
    ];

    return {
        stdout: lines.join('\n'),
        'k6-reservation-normal-load-summary.json': JSON.stringify(data, null, 2),
    };
}

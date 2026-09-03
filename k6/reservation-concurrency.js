import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import exec from 'k6/execution';

const BASE_URL = __ENV.BASE_URL;
if (!BASE_URL) {
    throw new Error(
        'BASE_URL 환경변수가 필요합니다. 예: k6 run -e BASE_URL=http://localhost:8080 k6/reservation-concurrency.js'
    );
}

const USER_COUNT = __ENV.USER_COUNT ? parseInt(__ENV.USER_COUNT, 10) : 10;
if (!Number.isInteger(USER_COUNT) || USER_COUNT < 1) {
    throw new Error(`USER_COUNT는 1 이상의 정수여야 합니다. 입력값: "${__ENV.USER_COUNT}"`);
}

const TEST_PASSWORD = 'K6-Load-Test-1234!';

const RUN_ID = Date.now();

http.setResponseCallback(http.expectedStatuses(200, 201, 409));

const reservationSuccess = new Counter('reservation_success');
const reservationConflict = new Counter('reservation_conflict');
const reservationLockConflict = new Counter('reservation_lock_conflict');
const reservationTimeConflict = new Counter('reservation_time_conflict');
const reservationUnexpected = new Counter('reservation_unexpected');
const reservationDuration = new Trend('reservation_duration', true);

const LOCK_CONFLICT_MESSAGE = '해당 시간대의 예약 요청이 처리 중입니다.';
const TIME_CONFLICT_MESSAGE = '이미 예약된 시간과 겹칩니다.';

export const options = {
    scenarios: {
        concurrent_reservation: {
            executor: 'per-vu-iterations',
            vus: USER_COUNT,
            iterations: 1,
            maxDuration: '2m',
        },
    },
    thresholds: {
        http_req_failed: ['rate<0.01'],
        checks: ['rate==1'],
        reservation_success: ['count==1'],
        reservation_unexpected: ['count==0'],
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

function findAvailableStartAt(token, resourceId) {
    const MAX_LOOKAHEAD_DAYS = 30;
    const today = new Date();

    for (let dayOffset = 1; dayOffset <= MAX_LOOKAHEAD_DAYS; dayOffset++) {
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

        const slots = availabilityRes.json('slots') || [];
        const availableSlots = slots.filter((slot) => slot.available === true);
        if (availableSlots.length > 0) {
            const index = RUN_ID % availableSlots.length;
            return availableSlots[index].startAt;
        }
    }

    throw new Error(
        `[setup] Resource(id=${resourceId})에서 향후 ${MAX_LOOKAHEAD_DAYS}일 내 예약 가능한 슬롯을 찾지 못했습니다.`
    );
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

    const activeResource = content.find((r) => r.status === 'ACTIVE');
    if (!activeResource) {
        throw new Error('[setup] ACTIVE 상태인 Resource가 없습니다. 테스트를 진행할 수 없습니다.');
    }

    const policy = activeResource.reservationPolicy;
    if (!policy || !policy.minDuration) {
        throw new Error(`[setup] Resource(id=${activeResource.id})에 예약 정책(minDuration)이 없습니다.`);
    }

    const duration = policy.minDuration;

    const startAt = findAvailableStartAt(tokens[0], activeResource.id);

    console.log(
        `[setup] 완료 - resourceId=${activeResource.id}, resourceName=${activeResource.name}, ` +
            `startAt=${startAt}, duration=${duration}분, USER_COUNT=${USER_COUNT}`
    );

    return {
        tokens,
        resourceId: activeResource.id,
        startAt,
        duration,
        fireAt: Date.now() + 3000,
    };
}

export default function (data) {
    const tokenIndex = (exec.vu.idInTest - 1) % data.tokens.length;
    const token = data.tokens[tokenIndex];

    const waitMs = data.fireAt - Date.now();
    if (waitMs > 0) {
        sleep(waitMs / 1000);
    }

    const payload = JSON.stringify({
        resourceId: data.resourceId,
        startAt: data.startAt,
        duration: data.duration,
    });

    const res = http.post(`${BASE_URL}/api/reservations`, payload, {
        headers: jsonHeaders(token),
        tags: { name: 'ReserveConcurrent' },
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
            console.warn(`[409-미분류] VU=${exec.vu.idInTest} message="${message}"`);
        }
    } else {
        reservationUnexpected.add(1);
        console.error(`[unexpected] VU=${exec.vu.idInTest} status=${res.status} body=${res.body}`);
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
    const total = success + conflict + unexpected;

    const durationMetric = data.metrics.reservation_duration;
    const avgDuration = durationMetric ? durationMetric.values.avg.toFixed(2) : 'N/A';
    const p95Duration = durationMetric ? durationMetric.values['p(95)'].toFixed(2) : 'N/A';
    const maxDuration = durationMetric ? durationMetric.values.max.toFixed(2) : 'N/A';

    const httpFailedRate = data.metrics.http_req_failed
        ? (data.metrics.http_req_failed.values.rate * 100).toFixed(2)
        : 'N/A';

    const pass = success === 1 && unexpected === 0;

    const lines = [
        '',
        '===== 예약 동시성 테스트 결과 =====',
        `총 동시 예약 요청 수                : ${total}`,
        `201 성공 (reservation_success)      : ${success}`,
        `409 충돌 합계 (reservation_conflict)          : ${conflict}`,
        `  ㄴ Redis Lock 충돌 (reservation_lock_conflict) : ${lockConflict}`,
        `  ㄴ DB 시간 중복 충돌 (reservation_time_conflict): ${timeConflict}`,
        `  ㄴ 미분류 409 (message 불일치)                 : ${unclassifiedConflict}`,
        `예상 밖 응답 (reservation_unexpected): ${unexpected}`,
        `예약 요청 응답시간 avg/p95/max (ms) : ${avgDuration} / ${p95Duration} / ${maxDuration}`,
        `HTTP 요청 실패율(http_req_failed)   : ${httpFailedRate}%`,
        `판정: ${pass ? 'PASS - 정확히 1건만 성공, 나머지는 모두 409로 처리됨' : 'FAIL - 성공 건수가 1건이 아니거나 예상 밖 응답이 발생함'}`,
        '====================================',
        '',
    ];

    return {
        stdout: lines.join('\n'),
        'k6-reservation-concurrency-summary.json': JSON.stringify(data, null, 2),
    };
}

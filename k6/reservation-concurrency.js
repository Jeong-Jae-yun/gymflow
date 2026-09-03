// GymFlow 예약 동시성 부하 테스트
//
// 목적:
//   동일한 resourceId + 동일한 startAt/duration 으로 USER_COUNT 명의 사용자가
//   "동시에" 예약을 요청했을 때, Redis 분산 락(ResourceAvailabilityLockRepository /
//   ReservationSlotLockRepository) + DB overlap 검증(ReservationService.doCreateReservation)이
//   정상 동작하여 정확히 1건만 201로 성공하고 나머지는 409로 충돌 처리되는지 검증한다.
//
// 실행 예:
//   k6 run -e BASE_URL=http://localhost:8080 -e USER_COUNT=10 k6/reservation-concurrency.js

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import exec from 'k6/execution';

// ---------------------------------------------------------------------------
// 설정값 (환경변수)
// ---------------------------------------------------------------------------

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

// 회원가입 시 password validation(@Size(min=8, max=64))을 통과하는 고정 테스트 비밀번호
const TEST_PASSWORD = 'K6-Load-Test-1234!';

// 실행마다 이메일/예약 시간이 겹치지 않도록 사용하는 run 식별자
const RUN_ID = Date.now();

// 409는 "정상적으로 예상된" 비즈니스 응답이므로 http_req_failed 기본 판정(4xx=실패) 대신
// 이 콜백으로 200/201/409 만 "성공"으로 간주하고, 그 외(5xx, 네트워크 오류 등)만 실패로 집계한다.
http.setResponseCallback(http.expectedStatuses(200, 201, 409));

// ---------------------------------------------------------------------------
// 커스텀 메트릭
// ---------------------------------------------------------------------------

const reservationSuccess = new Counter('reservation_success'); // 201
const reservationConflict = new Counter('reservation_conflict'); // 409 전체
const reservationLockConflict = new Counter('reservation_lock_conflict'); // 409 중 Redis 락 경쟁 (RESERVATION_IN_PROGRESS)
const reservationTimeConflict = new Counter('reservation_time_conflict'); // 409 중 DB 시간 중복 (RESERVATION_TIME_CONFLICT)
const reservationUnexpected = new Counter('reservation_unexpected'); // 그 외
const reservationDuration = new Trend('reservation_duration', true); // 예약 요청 응답시간(ms)

// ErrorCode.RESERVATION_IN_PROGRESS / RESERVATION_TIME_CONFLICT 의 메시지 원문 (ErrorCode.java 기준)
const LOCK_CONFLICT_MESSAGE = '해당 시간대의 예약 요청이 처리 중입니다.';
const TIME_CONFLICT_MESSAGE = '이미 예약된 시간과 겹칩니다.';

// ---------------------------------------------------------------------------
// 시나리오 옵션
//
// shared-iterations는 VU들이 큐에서 반복을 나눠 가지므로 "동시에 1회씩" 발사되는 것을
// 보장하지 않는다. per-vu-iterations + vus=USER_COUNT + iterations=1 은 각 VU가
// 정확히 1회만 실행하며 테스트 시작과 함께 모든 VU가 동시에 기동되므로 동시성 검증에 적합하다.
// (default 함수 안에서 공통 fireAt 시각까지 barrier-sleep 을 추가로 걸어 동시 발사 정밀도를 더 높인다.)
// ---------------------------------------------------------------------------

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
        // 실제 서버/네트워크 오류(5xx, timeout 등)만 실패로 집계된다 (200/201/409는 위 콜백으로 제외됨)
        http_req_failed: ['rate<0.01'],
        // 모든 예약 요청은 201 또는 409 여야 한다
        checks: ['rate==1'],
        // 정확히 1건만 성공해야 한다
        reservation_success: ['count==1'],
        // 예상 밖 응답(그 외 상태 코드)이 없어야 한다
        reservation_unexpected: ['count==0'],
    },
};

// ---------------------------------------------------------------------------
// 유틸
// ---------------------------------------------------------------------------

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

// java.time.LocalDate 가 기대하는 "yyyy-MM-dd" 형식으로 변환
function formatDate(date) {
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

// GET /api/resources/{resourceId}/availability?date=YYYY-MM-DD 를 이용해
// "실제로 비어 있는" 미래 슬롯의 startAt을 찾는다.
//
// 단순히 timestamp를 지터로 흩뿌리는 방식은 서로 다른 실행이 동일/중첩 시간을 고를 수 있는 반면,
// 이 방식은 매 실행 시점에 이미 생성된 예약(과거 실행분 포함)이 반영된 slot.available 결과를
// 그대로 사용하므로, 과거 실행에서 만든 예약과 절대 겹치지 않는다. slot.startAt은 정책
// (slotDuration/minDuration/maxDuration)과 하루 범위(00:00~24:00) 안에서만 생성되므로
// 운영시간/예약 정책 위반도 발생하지 않는다.
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
            // 실행마다(RUN_ID) 선택 인덱스를 바꿔, 동시에 실행된 별개의 k6 프로세스끼리도
            // 같은 슬롯을 고를 확률을 낮춘다.
            const index = RUN_ID % availableSlots.length;
            return availableSlots[index].startAt;
        }
    }

    throw new Error(
        `[setup] Resource(id=${resourceId})에서 향후 ${MAX_LOOKAHEAD_DAYS}일 내 예약 가능한 슬롯을 찾지 못했습니다.`
    );
}

// ---------------------------------------------------------------------------
// setup: 테스트 유저 준비 + 대상 Resource/시간 결정 (VU 시작 전 1회만 실행)
// ---------------------------------------------------------------------------

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

    // 첫 번째 사용자 토큰으로 Resource 목록 조회 후 ACTIVE 리소스 선택
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

    // ReservationService.doCreateReservation은 duration이 [minDuration, maxDuration] 범위인지만
    // 검증하므로(slotDuration 배수 여부는 검증하지 않음), minDuration을 그대로 사용하면 항상 유효하다.
    const duration = policy.minDuration;

    // 실제 가용 슬롯(availability API)에서 골라, 과거 실행에서 생성된 예약과 절대 겹치지 않는
    // startAt을 구한다. (자세한 이유는 findAvailableStartAt 주석 참고)
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
        // 모든 VU가 이 시각까지 대기했다가 거의 동시에 요청을 발사한다.
        fireAt: Date.now() + 3000,
    };
}

// ---------------------------------------------------------------------------
// 각 VU가 정확히 1회 실행하는 시나리오 본문
// ---------------------------------------------------------------------------

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

// ---------------------------------------------------------------------------
// 결과 요약 출력
// ---------------------------------------------------------------------------

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

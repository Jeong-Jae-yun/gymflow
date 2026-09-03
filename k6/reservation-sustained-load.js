// GymFlow 정상 예약 지속 부하(Sustained Load) 테스트
//
// 목적:
//   reservation-normal-load.js가 "barrier로 한 순간에 몰아서 쏘는" 순간 부하 테스트라면,
//   이 스크립트는 실제 서비스처럼 TEST_DURATION 동안 정상적인 예약 요청이 ramp-up → steady →
//   ramp-down 형태로 지속적으로 들어오는 상황을 재현해, HTTP latency/throughput과
//   DB Connection Pool, Redis latency, JVM 상태 등을 Grafana에서 관찰하기 위한 것이다.
//   동시성 경합(Redis Lock 충돌) 자체를 유도하는 테스트가 아니다.
//
// 테스트 종료 후 Grafana(backend/grafana/dashboards/gymflow-monitoring.json)에서 확인할 패널:
//   - HTTP Request Rate       : 설계한 ramp-up/steady/ramp-down 모양대로 실제 요청률이 따라가는지
//   - HTTP Response Time      : 부하가 늘어날 때 latency가 함께 늘어나는지(포화 여부)
//   - DB Connection Pool      : HikariCP 커넥션이 고갈되지 않는지
//   - Redis Command Latency   : Redis 분산락 SETNX 호출 지연이 안정적인지
//   - JVM Heap Memory Used    : 지속 부하 동안 힙이 계속 우상향(누수 의심)하지 않는지
//   - Reservation Requests    : Success/Conflict/Failed 추이가 이 스크립트의 커스텀 metric과 일치하는지
//   - Distributed Lock Attempts (lock_name별 acquired/failed) : resource-availability 락이
//     설계대로 거의 항상 acquired이고 failed가 드문지(= 인위적 경합이 잘 회피됐는지)
//   - Average Lock Wait Time  : (사용자가 말한 "Lock Attempt Latency"에 해당하는 실제 패널명)
//     SETNX 시도 자체의 지연이 안정적인지
//
// 실행 예:
//   k6 run -e BASE_URL=http://localhost:8080 -e USER_COUNT=20 -e TEST_DURATION=30s -e TARGET_RPS=5 \
//     k6/reservation-sustained-load.js

import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import exec from 'k6/execution';

// ---------------------------------------------------------------------------
// 설정값 (환경변수)
// ---------------------------------------------------------------------------

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

// steady 구간에서 유지할 목표 요청률(초당 예약 생성 요청 수). "정상 트래픽" 수준의 값으로,
// 필요하면 -e TARGET_RPS=로 조정한다.
const TARGET_RPS = __ENV.TARGET_RPS ? parseInt(__ENV.TARGET_RPS, 10) : 5;
if (!Number.isInteger(TARGET_RPS) || TARGET_RPS < 1) {
    throw new Error(`TARGET_RPS는 1 이상의 정수여야 합니다. 입력값: "${__ENV.TARGET_RPS}"`);
}

// "30s", "2m", "1h" 형태의 단일 단위 duration 문자열만 지원한다(k6 duration 표기의 부분집합).
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

// ramp-up 30% → steady 40% → ramp-down 30%. 시작부터 최대 부하를 주지 않고 서서히 올렸다가,
// steady 구간에서 TARGET_RPS를 유지한 뒤, 서서히 내린다.
const RAMP_UP_SECONDS = Math.max(1, Math.round(TOTAL_SECONDS * 0.3));
const RAMP_DOWN_SECONDS = Math.max(1, Math.round(TOTAL_SECONDS * 0.3));
const STEADY_SECONDS = Math.max(1, TOTAL_SECONDS - RAMP_UP_SECONDS - RAMP_DOWN_SECONDS);
const START_RATE = 1;
const RAMP_DOWN_END_RATE = 0;

// 회원가입 시 password validation(@Size(min=8, max=64))을 통과하는 고정 테스트 비밀번호
const TEST_PASSWORD = 'K6-Load-Test-1234!';

// 실행마다 이메일이 겹치지 않도록 사용하는 run 식별자
const RUN_ID = Date.now();

// 이 스크립트가 호출하는 API들의 정상 응답 status는 200(로그인/조회)/201(가입/예약 성공)/
// 409(예약 충돌, 정상적인 비즈니스 응답)뿐이다. 이 콜백으로 셋 다 "성공"으로 간주하고,
// 그 외(5xx, 네트워크 오류 등)만 http_req_failed 실패로 집계한다.
http.setResponseCallback(http.expectedStatuses(200, 201, 409));

// ---------------------------------------------------------------------------
// 커스텀 메트릭
// ---------------------------------------------------------------------------

const reservationSuccess = new Counter('reservation_success'); // 201
const reservationConflict = new Counter('reservation_conflict'); // 409 전체
const reservationLockConflict = new Counter('reservation_lock_conflict'); // 409 중 Redis 락 경쟁 (RESERVATION_IN_PROGRESS)
const reservationTimeConflict = new Counter('reservation_time_conflict'); // 409 중 DB 시간 중복 (RESERVATION_TIME_CONFLICT)
const reservationUnexpected = new Counter('reservation_unexpected'); // 그 외
const reservationDataExhausted = new Counter('reservation_data_exhausted'); // 사전 확보한 slot이 부족해 POST를 못 보낸 iteration
const reservationDuration = new Trend('reservation_duration', true); // 예약 요청 응답시간(ms)

// ErrorCode.RESERVATION_IN_PROGRESS / RESERVATION_TIME_CONFLICT 의 메시지 원문 (ErrorCode.java 기준)
const LOCK_CONFLICT_MESSAGE = '해당 시간대의 예약 요청이 처리 중입니다.';
const TIME_CONFLICT_MESSAGE = '이미 예약된 시간과 겹칩니다.';

// ---------------------------------------------------------------------------
// 부하 프로파일 / VU 풀 크기
//
// executor 선택: ramping-arrival-rate
//   - ramping-vus는 "VU 수"를 제어할 뿐 실제 요청률은 응답 지연에 따라 출렁인다. 이 테스트는
//     "정상 트래픽 처리량"을 관찰하는 게 목적이므로 요청률 자체를 직접 제어하는 arrival-rate 계열이
//     목적에 더 맞는다.
//   - 더 결정적인 이유: 예약 도메인 특성상 같은 (resourceId, startAt) 조합은 한 번만 성공할 수 있는
//     "1회용 데이터"다. ramping-vus는 VU마다 실제로 몇 번 반복될지가 응답 지연/총 시간에 따라
//     달라져서 setup()에서 필요한 예약 slot 총량을 미리 정확히 계산할 수 없다. 반면
//     ramping-arrival-rate/constant-arrival-rate는 (요청률 × 시간)으로 총 요청 수를 스크립트
//     작성 시점에 결정적으로 계산할 수 있어, 서로 겹치지 않는 예약 slot을 필요한 만큼 정확히
//     미리 확보해둘 수 있다. 이 결정성이 이 테스트에서는 실질적으로 더 중요하다.
// ---------------------------------------------------------------------------

// steady 구간 목표 요청률을 감당할 수 있도록 VU 풀을 넉넉히 잡는다. 평균 응답시간을 보수적으로
// 300ms로 가정하고 여유 배수를 곱한다(실제로 더 빠르면 VU가 남을 뿐 문제되지 않는다).
const ASSUMED_AVG_RESPONSE_SECONDS = 0.3;
const PRE_ALLOCATED_VUS = Math.max(10, Math.ceil(TARGET_RPS * ASSUMED_AVG_RESPONSE_SECONDS * 4));
const MAX_VUS = Math.max(PRE_ALLOCATED_VUS * 2, Math.ceil(TARGET_RPS * ASSUMED_AVG_RESPONSE_SECONDS * 8));

// ramp-up(사다리꼴 평균) + steady(직사각형) + ramp-down(사다리꼴 평균)의 넓이로 총 요청 수를 추정하고,
// 스케줄링 오차에 대비해 30% 여유 + 완충치를 더해 예약 slot 풀 크기를 정한다.
const estimatedRampUpRequests = ((START_RATE + TARGET_RPS) / 2) * RAMP_UP_SECONDS;
const estimatedSteadyRequests = TARGET_RPS * STEADY_SECONDS;
const estimatedRampDownRequests = ((TARGET_RPS + RAMP_DOWN_END_RATE) / 2) * RAMP_DOWN_SECONDS;
const ESTIMATED_TOTAL_REQUESTS = Math.ceil(
    estimatedRampUpRequests + estimatedSteadyRequests + estimatedRampDownRequests
);
const POOL_SIZE = Math.ceil(ESTIMATED_TOTAL_REQUESTS * 1.3) + 10;

// ---------------------------------------------------------------------------
// 시나리오 옵션
// ---------------------------------------------------------------------------

export const options = {
    scenarios: {
        sustained_reservation_load: {
            executor: 'ramping-arrival-rate',
            timeUnit: '1s',
            startRate: START_RATE,
            preAllocatedVUs: PRE_ALLOCATED_VUS,
            maxVUs: MAX_VUS,
            stages: [
                { target: TARGET_RPS, duration: `${RAMP_UP_SECONDS}s` }, // ramp-up
                { target: TARGET_RPS, duration: `${STEADY_SECONDS}s` }, // steady
                { target: RAMP_DOWN_END_RATE, duration: `${RAMP_DOWN_SECONDS}s` }, // ramp-down
            ],
        },
    },
    // USER_COUNT명 회원가입/로그인 + Resource별 예약 slot 사전 확보(availability 조회 다수)를
    // setup()에서 수행하므로 기본 60s보다 여유를 둔다.
    setupTimeout: '180s',
    summaryTrendStats: ['avg', 'p(95)', 'p(99)', 'max'],
    thresholds: {
        // 409는 정상적인 비즈니스 응답이므로(위 콜백으로 제외됨) 실제 서버/네트워크 오류만 집계된다.
        http_req_failed: ['rate<0.01'],
        // 예상 밖 응답(200/201/409 이외)이 없어야 한다.
        reservation_unexpected: ['count==0'],
        // 정상 트래픽 수준에서 p95 응답시간이 1초를 넘지 않아야 한다.
        http_req_duration: ['p(95)<1000'],
        // 사전 확보한 예약 slot이 부족해 요청을 못 보낸 iteration이 있으면 안 된다
        // (POOL_SIZE 추정이 부족했다는 뜻이므로 조용히 넘기지 않고 threshold로 드러낸다).
        reservation_data_exhausted: ['count==0'],
    },
};

// ---------------------------------------------------------------------------
// 유틸 (reservation-concurrency.js / reservation-normal-load.js와 동일한 방식)
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

// availability API가 주는 slot.startAt("yyyy-MM-ddTHH:mm:ss", 오프셋 없음)을
// 로컬 타임존 기준 Date로 파싱한다. 오프셋이 없는 ISO 문자열은 로컬 시간으로 해석된다.
function parseLocalDateTime(localDateTimeStr) {
    return new Date(localDateTimeStr);
}

// reservation-normal-load.js와 동일한 그리디 선택 로직을 그대로 재사용한다: 시간순 정렬된
// slot을 훑으며, 직전에 고른 slot의 종료 시각(pickedStart + duration) 이후에 시작하는 slot만
// 선택해 서로 절대 겹치지 않는 slot들만 모은다. 한 Resource에 배정되는 모든 slot은 반드시 이
// 함수(또는 이 함수를 감싸는 collectNonOverlappingStartAts) 단일 호출 흐름 안에서만 골라야
// cursorMs가 이어져서 전역 non-overlap이 보장된다 — Resource 하나를 여러 번에 나눠 별도로
// 호출하면 매번 cursorMs가 0부터 다시 시작해 서로 겹치는 slot을 고를 수 있다(이번에 고친 버그).
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

// GET /api/resources/{resourceId}/availability?date=YYYY-MM-DD 를 여러 날짜에 걸쳐 조회하며,
// 과거 실행에서 생성된 예약과 절대 겹치지 않는(=현재 시점 기준 실제로 비어 있는) 미래 slot을
// 최대 maxNeeded개까지 모은다. 날짜가 바뀌면 cursorMs를 0으로 재시작해도 안전한데, 다음 날짜의
// 첫 slot은 항상 이전 날짜의 마지막 slot보다 한참 뒤이기 때문이다.
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

// 한 Resource에 배정된 startAt 목록(시간순으로 이미 정렬되어 있어야 함)이 실제로 서로 겹치지
// 않는지 마지막으로 한 번 더 검증한다. previous.endAt <= current.startAt이 모든 인접 쌍에서
// 성립해야 하며, 하나라도 위반하면 테스트 시작 전에 즉시 에러로 종료한다.
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

// ---------------------------------------------------------------------------
// setup: 테스트 유저 준비 + 전역적으로 서로 겹치지 않는 예약 slot 풀을 사전 확보
//
// Resource Availability Lock이 resourceId 단위이기 때문에, 동시에 발생하는 요청이 같은
// resourceId를 쓰면 시간대가 달라도 409가 날 수 있다. 이를 줄이기 위해 최종 assignments
// 배열은 "Resource를 라운드로빈으로 순회하며 한 슬롯씩 채우는" 방식으로 만든다 — 이렇게 하면
// exec.scenario.iterationInTest(전역 순번, 실제 dispatch 순서와 그대로 대응)가 가까운
// iteration끼리는 서로 다른 Resource를 쓰게 된다(Resource가 N개면 인접 iteration끼리 같은
// Resource를 쓸 확률이 1/N로 낮아진다).
//
// 각 Resource별 slot은 반드시 "그 Resource에 배정된 전체 필요 수량"을 한 번의
// collectNonOverlappingStartAts() 호출로 확보한다 — 같은 Resource를 여러 번에 나눠 따로
// 호출하면(예: VU/레인마다 별도 호출) 매번 서버의 현재 DB 상태만 보고 처음부터 다시 고르기
// 때문에, 아직 실제로 생성되지 않은 다른 요청의 slot과 겹치는 시간을 중복으로 고를 수 있다.
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

    // 첫 번째 사용자 토큰으로 Resource 목록을 조회해 ACTIVE 리소스들을 모은다.
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

    // 정책이 없는 Resource는 예약을 만들 수 없으므로 미리 제외한다.
    const usableResources = activeResources.filter((r) => r.reservationPolicy && r.reservationPolicy.minDuration);
    if (usableResources.length === 0) {
        throw new Error('[setup] 예약 정책(minDuration)이 설정된 ACTIVE Resource가 없습니다.');
    }
    const skippedCount = activeResources.length - usableResources.length;
    if (skippedCount > 0) {
        console.warn(`[setup] 예약 정책이 없는 ACTIVE Resource ${skippedCount}개를 건너뜁니다.`);
    }

    // 1) Resource마다 필요한 만큼의 slot을 "한 번의 호출"로 확보한다 (전역 non-overlap의 핵심).
    const slotsPerResource = Math.ceil(POOL_SIZE / usableResources.length);
    const perResourcePlans = usableResources.map((resource) => {
        const duration = resource.reservationPolicy.minDuration;
        const startAts = collectNonOverlappingStartAts(tokens[0], resource.id, duration, slotsPerResource);
        return { resourceId: resource.id, duration, startAts };
    });

    // 2) 각 Resource별로 실제 겹치지 않는지 최종 검증한다 - 하나라도 겹치면 테스트를 시작하지 않는다.
    for (const plan of perResourcePlans) {
        assertNoOverlap(plan.resourceId, plan.startAts, plan.duration);
    }

    // 3) Resource를 라운드로빈으로 순회하며 한 슬롯씩 꺼내 전역 assignments 배열을 만든다.
    //    같은 Resource의 slot끼리는 assertNoOverlap을 통과했으므로 이 배열의 어느 위치에서
    //    꺼내 쓰더라도(=iteration 순번이 몇이든) 같은 Resource의 다른 배정과 절대 겹치지 않는다.
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

// ---------------------------------------------------------------------------
// 각 iteration마다 실행되는 시나리오 본문
//
// barrier 없이, ramping-arrival-rate가 스케줄링하는 시점에 곧바로 요청을 보낸다.
//
// unique 데이터 배정 방법: __VU/__ITER 조합 대신 exec.scenario.iterationInTest를 사용한다.
// arrival-rate executor는 VU를 재사용하므로(빨리 끝난 VU가 다음 요청을 또 맡음) 특정 VU의
// __ITER가 사전에 가정한 한도를 넘어설 수 있고, __VU 자체도 VU 재사용/증설 방식 때문에
// "이 VU가 지금까지 몇 번째로 실행됐는지"만 알려줄 뿐 "전체 테스트에서 몇 번째 요청인지"는
// 알려주지 않는다. 반면 exec.scenario.iterationInTest는 이 시나리오의 모든 VU를 통틀어
// 0부터 시작해 정확히 1씩 증가하는 전역 순번이므로, 어떤 VU가 몇 번을 재사용되든 상관없이
// 매 iteration마다 겹치지 않는 고유한 인덱스를 얻을 수 있다 - assignments 배열 인덱싱에
// 정확히 필요한 성질이다.
// ---------------------------------------------------------------------------

export default function (data) {
    const index = exec.scenario.iterationInTest;
    const assignment = data.assignments[index];

    if (!assignment) {
        // 사전 확보한 예약 slot 풀(POOL_SIZE)을 다 써버린 경우 - 조용히 넘기지 않고 명시적으로 집계한다.
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
    const dataExhausted = countOf('reservation_data_exhausted');
    const totalRequests = success + conflict + unexpected;

    // k6가 자체적으로 세는 완료된 iteration 수 (데이터가 없어 POST를 건너뛴 iteration도 포함).
    const iterationCount = data.metrics.iterations ? data.metrics.iterations.values.count : 0;
    const iterationsMatchRequests = iterationCount === totalRequests;

    // 실제 부하 구간(ramp-up+steady+ramp-down) 길이를 기준으로 직접 계산한다.
    // (k6 기본 http_reqs.rate는 setup() 중 발생한 회원가입/로그인/조회 요청까지 섞여 부정확해질 수 있다.)
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

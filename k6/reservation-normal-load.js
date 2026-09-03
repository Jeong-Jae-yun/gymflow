// GymFlow 비경합 정상 예약 부하 테스트
//
// 목적:
//   reservation-concurrency.js가 "동일 resourceId+시간에 대한 경합"을 검증하는 것과 달리,
//   이 스크립트는 서로 절대 겹치지 않는 예약을 USER_COUNT명이 동시에 요청했을 때
//   Redis 분산 락/DB overlap 검증에 걸리지 않고 정상적으로 전원 201을 받는지,
//   그리고 그 상황에서의 응답 성능(레이턴시)이 어떤지를 측정한다.
//   동시성 안전성 테스트가 아니라 "정상 트래픽 처리량/성능" 테스트다.
//
// 중요 - Resource Availability Lock은 resourceId 단위:
//   ReservationService.doCreateReservation()은 예약을 만들기 전에
//   ResourceAvailabilityLockRepository.tryLock(resourceId)로 "resourceId 하나당 락 하나"를 먼저 잡는다.
//   즉 시간대가 서로 겹치지 않더라도, 같은 resourceId로 여러 요청이 동시에 들어오면 그중 하나만 이
//   락을 잡고 나머지는 그 즉시 RESERVATION_IN_PROGRESS(409)를 받는다. 그래서 "비경합"을 보장하려면
//   시간대뿐 아니라 resourceId 자체도 VU마다 겹치지 않게 해야 한다 — 이 스크립트는 가능한 한
//   VU 1명당 서로 다른 ACTIVE Resource 1개를 전담시키는 방식으로 이를 보장한다.
//
// 실행 예:
//   k6 run -e BASE_URL=http://localhost:8080 -e USER_COUNT=50 k6/reservation-normal-load.js

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
        'BASE_URL 환경변수가 필요합니다. 예: k6 run -e BASE_URL=http://localhost:8080 k6/reservation-normal-load.js'
    );
}

const USER_COUNT = __ENV.USER_COUNT ? parseInt(__ENV.USER_COUNT, 10) : 50;
if (!Number.isInteger(USER_COUNT) || USER_COUNT < 1) {
    throw new Error(`USER_COUNT는 1 이상의 정수여야 합니다. 입력값: "${__ENV.USER_COUNT}"`);
}

// 회원가입 시 password validation(@Size(min=8, max=64))을 통과하는 고정 테스트 비밀번호
const TEST_PASSWORD = 'K6-Load-Test-1234!';

// 실행마다 이메일/예약 시간이 겹치지 않도록 사용하는 run 식별자
const RUN_ID = Date.now();

// 이 스크립트가 실제로 호출하는 API들의 정상 응답 status는 200(로그인/조회)/201(가입/예약 성공)/
// 409(예약 충돌) 뿐이다. 이 콜백으로 셋 다 "성공"으로 간주하고, 그 외(5xx, 네트워크 오류 등)만
// http_req_failed 실패로 집계한다.
http.setResponseCallback(http.expectedStatuses(200, 201, 409));

// ---------------------------------------------------------------------------
// 커스텀 메트릭
// ---------------------------------------------------------------------------

const reservationSuccess = new Counter('reservation_success'); // 201
const reservationConflict = new Counter('reservation_conflict'); // 409 (정상 트래픽에서는 발생하면 안 됨)
const reservationUnexpected = new Counter('reservation_unexpected'); // 그 외
const reservationDuration = new Trend('reservation_duration', true); // 예약 요청 응답시간(ms)

// ---------------------------------------------------------------------------
// 시나리오 옵션
//
// reservation-concurrency.js와 동일하게 shared-iterations 대신 per-vu-iterations를 사용한다.
// 여기서는 "경합"이 아니라 "서로 겹치지 않는 예약을 동시에 처리할 때의 정상 처리량/성능"을 보는
// 것이 목적이지만, 그래도 실제 서비스의 피크 트래픽(여러 사용자가 거의 동시에 몰리는 상황)을
// 재현하기 위해 동일한 start barrier(fireAt) 방식으로 최대한 동시에 요청을 발사한다.
// ---------------------------------------------------------------------------

export const options = {
    scenarios: {
        normal_load_reservation: {
            executor: 'per-vu-iterations',
            vus: USER_COUNT,
            iterations: 1,
            maxDuration: '2m',
        },
    },
    // USER_COUNT만큼 회원가입+로그인+가용시간 조회를 순차로 수행하므로 기본 60s보다 여유를 둔다.
    setupTimeout: '180s',
    thresholds: {
        // 실제 서버/네트워크 오류(5xx, timeout 등)만 실패로 집계된다 (200/201/409는 위 콜백으로 제외됨)
        http_req_failed: ['rate<0.01'],
        // 모든 예약 요청은 201 또는 409 여야 한다
        checks: ['rate==1'],
        // 예상 밖 응답(그 외 상태 코드)이 없어야 한다
        reservation_unexpected: ['count==0'],
        // 서로 겹치지 않는 예약이므로 정상적으로는 USER_COUNT건 전부 201이어야 한다.
        // 409가 하나라도 발생하면 이 threshold가 실패로 표시되어, 결과를 조용히 숨기지 않는다.
        reservation_success: [`count==${USER_COUNT}`],
    },
};

// ---------------------------------------------------------------------------
// 유틸 (reservation-concurrency.js와 동일한 방식)
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

// ---------------------------------------------------------------------------
// 서로 겹치지 않는 예약 slot 선택
//
// availability API의 slot 목록은 slotDuration 간격의 촘촘한 grid라서, 우리가 실제로 예약에
// 사용할 duration(=policy.minDuration)이 slotDuration보다 크면 "개별적으로는 available=true인"
// 인접 slot끼리도 실제 예약 구간은 서로 겹칠 수 있다. 그래서 시간순으로 정렬된 slot을 그리디하게
// 훑으면서, 직전에 고른 slot의 종료 시각(pickedStart + duration) 이후에 시작하는 slot만 선택한다.
// 이렇게 고른 slot들은 서로 절대 겹치지 않는다는 것이 보장된다.
// ---------------------------------------------------------------------------

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
// 최대 maxNeeded개까지 모은다.
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

// ---------------------------------------------------------------------------
// setup: 테스트 유저 준비 + 서로 겹치지 않는 (resourceId, startAt, duration) 조합 USER_COUNT개 확보
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

    // Resource Availability Lock이 resourceId 단위이므로, 동시에 발사되는 VU끼리 resourceId가
    // 겹치면 시간대가 달라도 409(RESERVATION_IN_PROGRESS)가 발생한다. 따라서 "비경합"을 보장하려면
    // ACTIVE Resource 수가 최소 USER_COUNT개는 있어야 하며, 이를 숨기지 않고 여기서 명확히 실패시킨다.
    if (activeResources.length < USER_COUNT) {
        throw new Error(
            '[setup] 비경합 정상 부하 테스트에는 USER_COUNT개 이상의 ACTIVE Resource가 필요합니다.\n' +
                `현재 ACTIVE Resource: ${activeResources.length}, USER_COUNT: ${USER_COUNT}`
        );
    }

    // VU 1명당 서로 다른 ACTIVE Resource를 정확히 1개씩 전담시킨다(resourceId 자체가 겹치지 않도록).
    // 각 Resource 내부에서는 기존 collectNonOverlappingStartAts()/pickNonOverlappingStartAts()로
    // 시간 구간이 겹치지 않는 slot을 1개 고른다. 후보 Resource에 가용 slot이 없으면 건너뛰고 다음
    // ACTIVE Resource로 넘어간다(그래서 activeResources 전체를 순회 대상으로 삼는다).
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
        // 모든 VU가 이 시각까지 대기했다가 거의 동시에 요청을 발사한다.
        fireAt: Date.now() + 3000,
    };
}

// ---------------------------------------------------------------------------
// 각 VU가 정확히 1회 실행하는 시나리오 본문 (VU마다 서로 다른 사용자 + 서로 겹치지 않는 예약)
// ---------------------------------------------------------------------------

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
        // 서로 겹치지 않게 설계했으므로 정상적으로는 발생하면 안 된다. 조용히 넘기지 않고 로그로 남긴다.
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

// ---------------------------------------------------------------------------
// 결과 요약 출력
// ---------------------------------------------------------------------------

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

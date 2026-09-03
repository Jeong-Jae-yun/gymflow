// GymFlow Waiting Queue / Promotion 시나리오 검증 테스트
//
// 목적:
//   여러 사용자가 이미 예약이 꽉 찬 동일 Resource/시간대의 Waiting Queue에 몰릴 때의 상태 전이를
//   end-to-end로 검증한다: 대기열 진입(join) → 예약 취소로 인한 다음 대기자 승급(Promotion 생성)
//   → 승급된 사용자의 reject → 그다음 대기자에게 재승급 → accept → 새 Reservation 생성 확인.
//   이 스크립트는 "얼마나 빠른가"가 아니라 "구현된 상태 전이가 실제로 맞게 동작하는가"를 검증하는
//   기능 검증(functional) 성격의 부하 테스트라서, 반복적으로 요청을 뿌리는 처리량 테스트가 아니라
//   딱 한 번의 흐름(Phase A → B → C)을 실행한다. per-vu-iterations(vus=1, iterations=1)를 쓰는
//   이유도 여기에 있다 — 여러 번 반복하면 두 번째 시도부터는 이미 취소/승급/수락이 끝난 예약
//   데이터라 동일한 시나리오를 재현할 수 없다.
//
// 실제 구현 확인 결과(코드 작성 전 조사):
//   - Waiting Queue 진입 전제조건: WaitingQueueService.registerWaitingQueue()는 해당
//     resourceId+startAt+endAt에 "이미 겹치는 OCCUPYING 예약(CONFIRMED/CHECKED_IN)이 존재해야만"
//     등록을 허용한다(WaitingQueueService.java). 즉 자리가 비어 있으면 오히려 409
//     WAITING_QUEUE_NOT_AVAILABLE로 거부된다 — 그래서 setup()에서 holder 계정으로 먼저 그
//     슬롯을 실제로 예약해서 "꽉 찬 상태"를 만들어야 한다.
//   - Promotion 생성 트리거: ReservationService.cancelReservation() → tryPromoteWaitingQueue()
//     → PromotionProcessor.tryPromote()가 취소와 "같은 트랜잭션" 안에서 동기 실행된다. 즉 cancel
//     API가 200을 반환한 시점에 이미 다음 대기자의 WaitingQueue.status가 PROMOTED로 바뀌고
//     WaitingQueuePromotion(status=OFFERED) row도 commit되어 있다.
//   - promotionId를 알아내는 REST 경로: PromotionController에는 GET이 없다. 유일한 REST 경로는
//     GET /api/waiting-queues(내 대기열 목록)에서 status=PROMOTED인 항목의 promotionId 필드를
//     읽는 것이다(WaitingQueueResponse.promotionId, WaitingQueueService.resolvePromotionId()).
//   - reject 후 재승급: PromotionService.reject()는 REJECTED를 commit한 뒤, 같은 메서드 안에서
//     promotionProcessor.tryPromote(...)를 다시 호출해 그다음 WAITING 후보를 OFFERED로 승급한다
//     (PromotionService.java). 이 스크립트는 이 구조를 그대로 이용해 "1등은 reject, 그 결과로
//     승급된 2등은 accept"로 accept/reject 두 경로를 모두 한 번의 흐름 안에서 검증한다.
//   - accept 성공 시 PromotionAcceptResponse에 reservationId가 바로 포함되므로, 별도 목록 조회
//     없이도 새 Reservation 생성 여부를 즉시 알 수 있다(추가로 GET /api/reservations/{id}로
//     상태까지 재확인한다).
//
// 실행 예:
//   k6 run -e BASE_URL=http://localhost:8080 -e USER_COUNT=20 k6/waiting-queue-promotion-load.js
//
// 테스트 중/후 Grafana(backend/grafana/dashboards/gymflow-monitoring.json)에서 확인할 것:
//   - Waiting Queue Activity / Current Waiting Queue Size : Phase A에서 대기열 크기가 늘었다가
//     Phase B/C에서 승급·수락으로 줄어드는 추이
//   - Promotion Outcomes                                  : OFFERED/ACCEPTED/REJECTED 건수가
//     이 스크립트의 promotion_reject_success/promotion_accept_success와 맞는지
//   - Reservation Requests / Reservation Conflict Rate     : holder의 최초 예약 생성, accept로
//     생성된 신규 예약이 반영되는지
//   - Distributed Lock Attempts (lock_name="resource-availability"/"reservation-slot") : cancel→
//     promote, reject→promote, accept 각각에서 Lock 획득이 정상적으로 이뤄지는지
//   - WebSocket Notifications (gymflow_websocket_notification_sent_total) : 이 스크립트는 STOMP
//     WebSocket에 직접 연결하지 않는다(요청사항에 따라 HTTP/API만 source of truth로 사용).
//     대신 Promotion이 생성될 때마다 WaitingQueueEventSubscriber가 STOMP push를 보내면서 이
//     metric을 1씩 증가시키므로, 이 테스트 실행 전후로 Grafana에서 이 카운터가 늘었는지 직접
//     확인하면 "REST로 확인한 상태 전이"와 "실제 WebSocket 알림 발송"이 함께 일어났는지 교차검증할
//     수 있다.

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

// ---------------------------------------------------------------------------
// 설정값 (환경변수)
// ---------------------------------------------------------------------------

const BASE_URL = __ENV.BASE_URL;
if (!BASE_URL) {
    throw new Error(
        'BASE_URL 환경변수가 필요합니다. 예: k6 run -e BASE_URL=http://localhost:8080 k6/waiting-queue-promotion-load.js'
    );
}

// Waiting Queue에 진입할 사용자 수(대기열 1등 reject → 2등 accept 흐름을 검증하려면 최소 2명 필요).
const USER_COUNT = __ENV.USER_COUNT ? parseInt(__ENV.USER_COUNT, 10) : 20;
if (!Number.isInteger(USER_COUNT) || USER_COUNT < 2) {
    throw new Error(`USER_COUNT는 2 이상의 정수여야 합니다(1등 reject, 2등 accept 검증에 필요). 입력값: "${__ENV.USER_COUNT}"`);
}

// 회원가입 시 password validation(@Size(min=8, max=64))을 통과하는 고정 테스트 비밀번호
const TEST_PASSWORD = 'K6-Load-Test-1234!';

// 실행마다 이메일/예약 시간이 겹치지 않도록 사용하는 run 식별자
const RUN_ID = Date.now();

// holder 예약용 slot 탐색을 시작할 날짜 offset(내일부터 며칠 뒤부터 훑을지, 1~14일 사이).
// RUN_ID(ms)를 기반으로 실행마다 다른 값을 골라서, 연달아 실행해도 서로 다른 날짜 구간부터
// 탐색하게 만든다 - 직전 실행이 만든 예약/WaitingQueue/Promotion 상태와 마주칠 확률을 줄인다.
// (완전한 격리는 아니며, 실제 안전망은 findAndReserveHolderSlot의 409 skip-and-retry다.)
const RUN_START_DAY_OFFSET = 1 + (RUN_ID % 14);

// 이 스크립트가 호출하는 API들의 정상 응답 status: 200(로그인/조회/cancel/accept),
// 201(가입/예약 생성/대기열 등록 성공), 204(대기열 취소/reject 성공), 409(대기열/예약 비즈니스
// 충돌 - 정상적인 응답). 그 외(5xx, 네트워크 오류)만 http_req_failed 실패로 집계한다.
http.setResponseCallback(http.expectedStatuses(200, 201, 204, 409));

// ---------------------------------------------------------------------------
// 커스텀 메트릭
// ---------------------------------------------------------------------------

const queueJoinSuccess = new Counter('queue_join_success'); // 201
const queueJoinConflict = new Counter('queue_join_conflict'); // 409
const queueJoinUnexpected = new Counter('queue_join_unexpected'); // 그 외
const queueRankValidationFailed = new Counter('queue_rank_validation_failed'); // waitingRank 중복/불일치

const promotionCreated = new Counter('promotion_created'); // cancel 이후 PROMOTED 상태 관측 성공
const promotionAcceptSuccess = new Counter('promotion_accept_success');
const promotionRejectSuccess = new Counter('promotion_reject_success');
const promotionUnexpected = new Counter('promotion_unexpected'); // 예상 밖 응답/상태(생성 실패, accept/reject 실패 등)

const flowDuration = new Trend('flow_duration', true); // 이 스크립트가 만드는 모든 요청의 응답시간(ms)

// ---------------------------------------------------------------------------
// 시나리오 옵션
//
// 반복 처리량이 아니라 "한 번의 시나리오 흐름"을 검증하는 테스트이므로 vus=1, iterations=1의
// per-vu-iterations를 사용한다. Phase A(대기열 진입)만 http.batch()로 짧은 시간 안에
// 동시 발사한다.
// ---------------------------------------------------------------------------

export const options = {
    scenarios: {
        waiting_queue_promotion_flow: {
            executor: 'per-vu-iterations',
            vus: 1,
            iterations: 1,
            maxDuration: '2m',
        },
    },
    setupTimeout: '120s',
    thresholds: {
        http_req_failed: ['rate<0.01'],
        queue_join_unexpected: ['count==0'],
        promotion_unexpected: ['count==0'],
        queue_rank_validation_failed: ['count==0'],
        // 핵심 상태 전이 검증: 하나라도 실패하면 threshold 자체가 FAIL로 표시된다.
        promotion_created: ['count==1'],
        promotion_reject_success: ['count==1'],
        promotion_accept_success: ['count==1'],
    },
};

// ---------------------------------------------------------------------------
// 유틸 (기존 k6 스크립트들과 동일한 방식)
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

function formatDate(date) {
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

function formatLocalDateTime(date) {
    return (
        `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}` +
        `T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
    );
}

function parseLocalDateTime(localDateTimeStr) {
    return new Date(localDateTimeStr);
}

function addMinutes(localDateTimeStr, minutes) {
    const date = parseLocalDateTime(localDateTimeStr);
    date.setMinutes(date.getMinutes() + minutes);
    return formatLocalDateTime(date);
}

// reservation-normal-load.js / reservation-sustained-load.js와 동일한 그리디 선택 로직.
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

// GET /api/resources/{resourceId}/availability?date=YYYY-MM-DD 를 여러 날짜에 걸쳐 조회하며
// 과거 실행과 겹치지 않는 미래 slot을 최대 maxNeeded개까지 모은다. (기존 스크립트들과 동일)
//
// startDayOffset(기본 1 = "내일부터")을 바꿀 수 있게 해서, 실행마다 탐색을 시작하는 날짜 자체를
// 흩뿌릴 수 있게 한다 - RUN_ID 기반으로 이 값을 바꾸면 서로 다른 실행이 같은 날짜부터 훑지 않으므로
// 최근 실행이 남긴 상태와 마주칠 확률이 줄어든다(findAndReserveHolderSlot의 재시도와 함께 쓰인다).
//
// 주의: 여기서 available=true는 "겹치는 예약이 없다"만 의미하고, 대기열 승급 우선권
// (RESERVATION_PROMOTION_RESERVED)까지는 반영하지 않는다(availability API는 그 정보를 모른다).
// 그래서 이 함수가 돌려주는 후보들도 실제로는 예약 생성이 거부될 수 있다 - 그 처리는 호출부
// (findAndReserveHolderSlot)의 몫이다.
function collectNonOverlappingStartAts(token, resourceId, duration, maxNeeded, startDayOffset) {
    const MAX_LOOKAHEAD_DAYS = 30;
    const firstDayOffset = startDayOffset || 1;
    const today = new Date();
    const results = [];

    for (
        let dayOffset = firstDayOffset;
        dayOffset < firstDayOffset + MAX_LOOKAHEAD_DAYS && results.length < maxNeeded;
        dayOffset++
    ) {
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

// holder 예약 생성 후보를 최대 HOLDER_RESERVATION_MAX_ATTEMPTS개까지 순서대로 시도한다.
// availability API는 대기열 승급 우선권(RESERVATION_PROMOTION_RESERVED)이나, 다른 요청이
// 동시에 같은 슬롯을 잡으려는 상황(RESERVATION_IN_PROGRESS), 그사이 다른 곳에서 실제로 예약이
// 생성된 상황(RESERVATION_TIME_CONFLICT)을 알지 못하므로, "available=true"였던 후보도 실제
// POST /api/reservations에서 409로 거부될 수 있다. 이런 409는 "이 slot은 지금 못 쓴다"는
// 정상적인 신호일 뿐 setup 자체의 실패가 아니므로, 즉시 중단하지 않고 다음 후보로 넘어간다.
// 반대로 409가 아닌 다른 예상 밖 응답(5xx 등)은 진짜 오류이므로 그 자리에서 바로 실패시킨다.
const HOLDER_RESERVATION_MAX_ATTEMPTS = 20;

function findAndReserveHolderSlot(holderToken, resourceId, duration) {
    const candidates = collectNonOverlappingStartAts(
        holderToken, resourceId, duration, HOLDER_RESERVATION_MAX_ATTEMPTS, RUN_START_DAY_OFFSET);
    if (candidates.length === 0) {
        throw new Error(
            `[setup] Resource(id=${resourceId})에서 예약 가능한 slot 후보를 하나도 찾지 못했습니다 ` +
                `(탐색 시작일 offset=${RUN_START_DAY_OFFSET}일).`
        );
    }

    const skipped = [];
    for (const startAt of candidates) {
        const holderReservationRes = http.post(
            `${BASE_URL}/api/reservations`,
            JSON.stringify({ resourceId, startAt, duration }),
            { headers: jsonHeaders(holderToken) }
        );

        if (holderReservationRes.status === 201) {
            const holderReservationId = holderReservationRes.json('reservationId');
            if (!holderReservationId) {
                throw new Error(`[setup] holder 예약 응답에 reservationId가 없습니다: ${holderReservationRes.body}`);
            }
            if (skipped.length > 0) {
                console.warn(
                    `[setup] 후보 slot ${skipped.length}개를 건너뛴 뒤 startAt=${startAt}에서 holder 예약을 확보했습니다.`
                );
            }
            return { startAt, holderReservationId };
        }

        if (holderReservationRes.status === 409) {
            // RESERVATION_TIME_CONFLICT / RESERVATION_IN_PROGRESS / RESERVATION_PROMOTION_RESERVED
            // (ErrorCode.java 기준) 모두 "이 slot은 지금 못 쓴다"는 동일한 의미로 취급해 건너뛴다.
            const message = holderReservationRes.json('message');
            skipped.push({ startAt, message });
            console.warn(`[setup] holder 예약 후보 충돌로 건너뜀 - startAt=${startAt}, message="${message}"`);
            continue;
        }

        throw new Error(
            `[setup] holder의 선점 예약 생성 중 예상치 못한 오류 (startAt=${startAt}, ` +
                `status=${holderReservationRes.status}): ${holderReservationRes.body}`
        );
    }

    const reasons = skipped.map((s) => `  - ${s.startAt}: ${s.message}`).join('\n');
    throw new Error(
        `[setup] 후보 slot ${candidates.length}개를 모두 시도했지만 holder 예약을 생성하지 못했습니다.\n${reasons}`
    );
}

function signUpAndLogin(index) {
    const email = `k6-wq-${RUN_ID}-${index}@test.com`;

    const signupRes = http.post(
        `${BASE_URL}/api/users/signup`,
        JSON.stringify({ email, password: TEST_PASSWORD, name: `k6-wq-user-${index}` }),
        { headers: jsonHeaders() }
    );
    if (signupRes.status !== 201) {
        throw new Error(`[setup] 회원가입 실패 (index=${index}, status=${signupRes.status}): ${signupRes.body}`);
    }

    const loginRes = http.post(
        `${BASE_URL}/api/auth/login`,
        JSON.stringify({ email, password: TEST_PASSWORD }),
        { headers: jsonHeaders() }
    );
    if (loginRes.status !== 200) {
        throw new Error(`[setup] 로그인 실패 (index=${index}, status=${loginRes.status}): ${loginRes.body}`);
    }

    const accessToken = loginRes.json('accessToken');
    if (!accessToken) {
        throw new Error(`[setup] 로그인 응답에 accessToken이 없습니다 (index=${index}): ${loginRes.body}`);
    }
    return accessToken;
}

// ---------------------------------------------------------------------------
// setup: 사용자 준비 + 대상 Resource/슬롯 결정 + holder의 "선점 예약" 생성
// ---------------------------------------------------------------------------

export function setup() {
    // index 0 = holder(먼저 예약을 잡아 슬롯을 채우는 사용자), 1..USER_COUNT = 대기열 진입자
    const tokens = [];
    for (let i = 0; i <= USER_COUNT; i++) {
        tokens.push(signUpAndLogin(i));
    }
    const holderToken = tokens[0];
    const queuerTokens = tokens.slice(1);

    const resourcesRes = http.get(`${BASE_URL}/api/resources?page=0&size=100`, {
        headers: jsonHeaders(holderToken),
    });
    if (resourcesRes.status !== 200) {
        throw new Error(`[setup] Resource 목록 조회 실패 (status=${resourcesRes.status}): ${resourcesRes.body}`);
    }
    const content = resourcesRes.json('content');
    if (!content || content.length === 0) {
        throw new Error('[setup] 조회된 Resource가 없습니다. 테스트를 진행할 수 없습니다.');
    }

    const activeResource = content.find(
        (r) => r.status === 'ACTIVE' && r.reservationPolicy && r.reservationPolicy.minDuration
    );
    if (!activeResource) {
        throw new Error('[setup] 예약 정책이 설정된 ACTIVE Resource가 없습니다. 테스트를 진행할 수 없습니다.');
    }

    // OFFERED Promotion의 TTL은 min(슬롯 종료까지 남은 시간 - minDuration, 2분)로 계산된다
    // (PromotionProcessor). 이 여유 시간을 최대한 확보하기 위해 duration은 minDuration이 아니라
    // maxDuration을 사용한다 - accept/reject를 순차로 처리할 시간을 넉넉히 벌기 위함이다.
    const policy = activeResource.reservationPolicy;
    const duration = policy.maxDuration || policy.minDuration;

    // holder가 먼저 이 슬롯을 실제로 예약해서 "꽉 찬 상태"를 만든다. WaitingQueueService는 이
    // 상태가 아니면(자리가 비어 있으면) 409 WAITING_QUEUE_NOT_AVAILABLE로 진입 자체를 거부한다.
    // availability=true였던 후보도 대기열 승급 우선권 등으로 실제 예약 생성은 거부될 수 있으므로,
    // 단일 후보만 시도하지 않고 findAndReserveHolderSlot이 여러 후보를 순서대로 시도한다.
    const { startAt, holderReservationId } = findAndReserveHolderSlot(holderToken, activeResource.id, duration);
    const endAt = addMinutes(startAt, duration);

    console.log(
        `[setup] 완료 - resourceId=${activeResource.id}, resourceName=${activeResource.name}, ` +
            `startAt=${startAt}, endAt=${endAt}, duration=${duration}분, ` +
            `holderReservationId=${holderReservationId}, 대기열 진입자 수(USER_COUNT)=${USER_COUNT}`
    );

    return {
        holderToken,
        queuerTokens,
        resourceId: activeResource.id,
        startAt,
        endAt,
        holderReservationId,
    };
}

// ---------------------------------------------------------------------------
// Phase 함수들
// ---------------------------------------------------------------------------

// Phase A: USER_COUNT명이 http.batch()로 짧은 시간 안에 동시에 Waiting Queue에 진입한다.
function joinWaitingQueueConcurrently(data) {
    const payload = JSON.stringify({
        resourceId: data.resourceId,
        startAt: data.startAt,
        endAt: data.endAt,
    });

    const batchRequests = data.queuerTokens.map((token) => ({
        method: 'POST',
        url: `${BASE_URL}/api/waiting-queues`,
        body: payload,
        params: { headers: jsonHeaders(token), tags: { name: 'WaitingQueueJoin' } },
    }));

    const responses = http.batch(batchRequests);

    const joined = [];
    responses.forEach((res, i) => {
        flowDuration.add(res.timings.duration);
        check(res, {
            '대기열 진입 응답 status가 201 또는 409': (r) => r.status === 201 || r.status === 409,
        });

        if (res.status === 201) {
            queueJoinSuccess.add(1);
            joined.push({
                // email이 `k6-wq-${RUN_ID}-${testUser}@test.com` 형태이므로(setup의 signUpAndLogin),
                // testUser는 민감정보 없이 이 요청을 보낸 사용자를 사람이 식별할 수 있는 번호다.
                testUser: i + 1,
                token: data.queuerTokens[i],
                waitingQueueId: res.json('waitingQueueId'),
                waitingRank: res.json('waitingRank'),
            });
        } else if (res.status === 409) {
            queueJoinConflict.add(1);
            console.warn(`[queue-join-conflict] index=${i} message="${res.json('message')}"`);
        } else {
            queueJoinUnexpected.add(1);
            console.error(`[queue-join-unexpected] index=${i} status=${res.status} body=${res.body}`);
        }
    });

    // 디버그 로그: 성공한 20건을 waitingRank 기준 오름차순으로 정렬해 그대로 출력한다.
    // JWT/password 등 민감정보는 포함하지 않는다(testUser 번호 + waitingQueueId + waitingRank만).
    const sortedForDebug = [...joined].sort((a, b) => a.waitingRank - b.waitingRank);
    console.log(`[phase-a-debug] 대기열 진입 성공 ${sortedForDebug.length}건 (waitingRank 오름차순):`);
    sortedForDebug.forEach((j) => {
        console.log(`  testUser=${j.testUser}, waitingQueueId=${j.waitingQueueId}, waitingRank=${j.waitingRank}`);
    });

    // 중복 QueueItem/순위 검증: 성공한 진입끼리 waitingQueueId와 waitingRank가 모두 서로 달라야 하고,
    // 최댓값 waitingRank가 성공 건수와 같아야(=1..N이 각각 정확히 한 번씩) 대기열 크기가 기대값과
    // 일치한다고 볼 수 있다(대기열 전체를 나열하는 관리자 API가 없어 이 방식으로 REST 응답만으로 검증한다).
    const waitingQueueIds = new Set(joined.map((j) => j.waitingQueueId));
    const waitingRanks = new Set(joined.map((j) => j.waitingRank));
    const maxRank = joined.length > 0 ? Math.max(...joined.map((j) => j.waitingRank)) : 0;

    const rankValid =
        waitingQueueIds.size === joined.length && waitingRanks.size === joined.length && maxRank === joined.length;

    if (!rankValid) {
        queueRankValidationFailed.add(1);

        // 어떤 rank가 중복이고 어떤 rank가 누락됐는지 정확히 짚어낸다.
        const rankOccurrences = new Map();
        for (const j of sortedForDebug) {
            const owners = rankOccurrences.get(j.waitingRank) || [];
            owners.push(j.testUser);
            rankOccurrences.set(j.waitingRank, owners);
        }
        const duplicatedRanks = [...rankOccurrences.entries()].filter(([, owners]) => owners.length > 1);
        const missingRanks = [];
        for (let r = 1; r <= maxRank; r++) {
            if (!rankOccurrences.has(r)) {
                missingRanks.push(r);
            }
        }

        console.error(
            `[queue-rank-validation] 실패 - 성공 진입 수=${joined.length}, ` +
                `고유 waitingQueueId 수=${waitingQueueIds.size}, 고유 waitingRank 수=${waitingRanks.size}, ` +
                `최댓값 waitingRank=${maxRank}`
        );
        console.error(
            `[queue-rank-validation] 중복된 waitingRank=${JSON.stringify(
                duplicatedRanks.map(([rank, owners]) => ({ rank, testUsers: owners }))
            )}`
        );
        console.error(`[queue-rank-validation] 누락된 waitingRank(1~${maxRank} 범위)=${JSON.stringify(missingRanks)}`);
    } else {
        console.log(
            `[queue-rank-validation] 통과 - 대기열 크기(성공 진입 수)=${joined.length}, ` +
                `waitingRank가 1..${joined.length}로 중복 없이 정확히 배정됨`
        );
    }

    // waitingRank 오름차순 정렬 - [0]이 1등(다음 승급 대상), [1]이 2등.
    joined.sort((a, b) => a.waitingRank - b.waitingRank);
    return joined;
}

// GET /api/waiting-queues에서 이 Resource/시간대에 대해 PROMOTED 상태 + promotionId가 채워진
// 항목을 찾는다. cancel/reject는 같은 트랜잭션에서 동기적으로 승급을 commit하므로 이론상 첫 시도에
// 바로 보여야 하지만, 방어적으로 짧은 재시도를 둔다.
function pollForPromotion(token, data, maxAttempts) {
    for (let attempt = 0; attempt < maxAttempts; attempt++) {
        const res = http.get(`${BASE_URL}/api/waiting-queues?page=0&size=50`, {
            headers: jsonHeaders(token),
            tags: { name: 'WaitingQueuePoll' },
        });
        flowDuration.add(res.timings.duration);

        if (res.status !== 200) {
            return { found: false, promotionId: null, lastStatus: res.status, lastBody: res.body };
        }

        const content = res.json('content') || [];
        const entry = content.find(
            (item) =>
                item.resourceId === data.resourceId &&
                item.startAt === data.startAt &&
                item.endAt === data.endAt &&
                item.status === 'PROMOTED' &&
                item.promotionId
        );
        if (entry) {
            return { found: true, promotionId: entry.promotionId, lastStatus: res.status };
        }
        sleep(0.3);
    }
    return { found: false, promotionId: null, lastStatus: 200 };
}

// ---------------------------------------------------------------------------
// 시나리오 본문 (vus=1, iterations=1 - 딱 한 번 전체 흐름을 수행한다)
// ---------------------------------------------------------------------------

export default function (data) {
    // ---- Phase A: Waiting Queue Join ----
    const joined = joinWaitingQueueConcurrently(data);
    if (joined.length < 2) {
        promotionUnexpected.add(1);
        console.error(
            `[phase-b/c 중단] 대기열 진입 성공자가 ${joined.length}명뿐이라 1등 reject → 2등 accept 흐름을 검증할 수 없습니다.`
        );
        return;
    }
    const firstInLine = joined[0];
    const secondInLine = joined[1];

    // ---- Phase B: holder가 예약을 취소해 1등을 Promotion(OFFERED)으로 승급시킨다 ----
    const cancelRes = http.patch(
        `${BASE_URL}/api/reservations/${data.holderReservationId}/cancel`,
        JSON.stringify({ cancelReason: 'PERSONAL_REASON' }),
        { headers: jsonHeaders(data.holderToken), tags: { name: 'HolderCancel' } }
    );
    flowDuration.add(cancelRes.timings.duration);
    check(cancelRes, { 'holder 예약 취소 status가 200': (r) => r.status === 200 });
    if (cancelRes.status !== 200) {
        promotionUnexpected.add(1);
        console.error(`[cancel-unexpected] status=${cancelRes.status} body=${cancelRes.body}`);
        return;
    }

    const firstPromotion = pollForPromotion(firstInLine.token, data, 5);
    if (!firstPromotion.found) {
        promotionUnexpected.add(1);
        console.error(
            `[promotion-not-observed] 1등(waitingQueueId=${firstInLine.waitingQueueId})의 승급을 확인하지 못했습니다. ` +
                `lastStatus=${firstPromotion.lastStatus}`
        );
        return;
    }
    promotionCreated.add(1);
    console.log(`[promotion-created] 1등 promotionId=${firstPromotion.promotionId}`);

    // ---- Phase C-1: 1등은 reject한다 (accept/reject 두 경로 모두 검증하기 위함) ----
    const rejectRes = http.post(`${BASE_URL}/api/promotions/${firstPromotion.promotionId}/reject`, null, {
        headers: jsonHeaders(firstInLine.token),
        tags: { name: 'PromotionReject' },
    });
    flowDuration.add(rejectRes.timings.duration);
    check(rejectRes, { 'promotion reject status가 204': (r) => r.status === 204 });
    if (rejectRes.status === 204) {
        promotionRejectSuccess.add(1);
    } else {
        promotionUnexpected.add(1);
        console.error(`[reject-unexpected] status=${rejectRes.status} body=${rejectRes.body}`);
        return;
    }

    // reject()는 REJECTED를 commit한 뒤 같은 호출 안에서 다음 대기자(2등)를 다시 승급시킨다
    // (PromotionService.reject → tryPromote). 2등의 승급을 폴링으로 확인한다.
    const secondPromotion = pollForPromotion(secondInLine.token, data, 5);
    if (!secondPromotion.found) {
        promotionUnexpected.add(1);
        console.error(
            `[promotion-not-observed] 2등(waitingQueueId=${secondInLine.waitingQueueId})의 재승급을 확인하지 못했습니다. ` +
                `lastStatus=${secondPromotion.lastStatus}`
        );
        return;
    }
    console.log(`[promotion-created] 2등(재승급) promotionId=${secondPromotion.promotionId}`);

    // ---- Phase C-2: 2등은 accept한다 ----
    const acceptRes = http.post(`${BASE_URL}/api/promotions/${secondPromotion.promotionId}/accept`, null, {
        headers: jsonHeaders(secondInLine.token),
        tags: { name: 'PromotionAccept' },
    });
    flowDuration.add(acceptRes.timings.duration);
    check(acceptRes, { 'promotion accept status가 200': (r) => r.status === 200 });
    if (acceptRes.status !== 200) {
        promotionUnexpected.add(1);
        console.error(`[accept-unexpected] status=${acceptRes.status} body=${acceptRes.body}`);
        return;
    }
    promotionAcceptSuccess.add(1);

    const newReservationId = acceptRes.json('reservationId');
    console.log(
        `[promotion-accepted] promotionStatus=${acceptRes.json('promotionStatus')}, newReservationId=${newReservationId}`
    );

    // accept 응답에 이미 reservationId가 포함되지만, 실제로 Reservation이 조회 가능한 상태인지
    // (CONFIRMED) GET으로 한 번 더 교차 확인한다.
    if (newReservationId) {
        const newReservationRes = http.get(`${BASE_URL}/api/reservations/${newReservationId}`, {
            headers: jsonHeaders(secondInLine.token),
            tags: { name: 'NewReservationCheck' },
        });
        flowDuration.add(newReservationRes.timings.duration);
        const isConfirmed = newReservationRes.status === 200 && newReservationRes.json('status') === 'CONFIRMED';
        check(newReservationRes, {
            'accept로 생성된 Reservation이 CONFIRMED 상태로 조회됨': () => isConfirmed,
        });
        if (!isConfirmed) {
            promotionUnexpected.add(1);
            console.error(
                `[new-reservation-unexpected] status=${newReservationRes.status} body=${newReservationRes.body}`
            );
        }
    } else {
        promotionUnexpected.add(1);
        console.error('[accept-unexpected] accept 응답에 reservationId가 없습니다.');
    }
}

// ---------------------------------------------------------------------------
// 결과 요약 출력
// ---------------------------------------------------------------------------

export function handleSummary(data) {
    const countOf = (name) => (data.metrics[name] ? data.metrics[name].values.count : 0);

    const joinSuccess = countOf('queue_join_success');
    const joinConflict = countOf('queue_join_conflict');
    const joinUnexpected = countOf('queue_join_unexpected');
    const joinTotal = joinSuccess + joinConflict + joinUnexpected;
    const rankValidationFailed = countOf('queue_rank_validation_failed');

    const created = countOf('promotion_created');
    const acceptSuccess = countOf('promotion_accept_success');
    const rejectSuccess = countOf('promotion_reject_success');
    const promoUnexpected = countOf('promotion_unexpected');

    const totalUnexpected = joinUnexpected + promoUnexpected;

    const durationMetric = data.metrics.flow_duration;
    const avgDuration = durationMetric ? durationMetric.values.avg.toFixed(2) : 'N/A';
    const p95Duration = durationMetric ? durationMetric.values['p(95)'].toFixed(2) : 'N/A';
    const maxDuration = durationMetric ? durationMetric.values.max.toFixed(2) : 'N/A';

    const httpFailedRate = data.metrics.http_req_failed
        ? (data.metrics.http_req_failed.values.rate * 100).toFixed(2)
        : 'N/A';
    const httpFailedRateNum = data.metrics.http_req_failed ? data.metrics.http_req_failed.values.rate : 0;

    const coreTransitionsOk = created === 1 && rejectSuccess === 1 && acceptSuccess === 1 && rankValidationFailed === 0;
    const pass = totalUnexpected === 0 && httpFailedRateNum < 0.01 && coreTransitionsOk;

    const failReasons = [];
    if (totalUnexpected !== 0) failReasons.push(`예상 밖 응답=${totalUnexpected}`);
    if (httpFailedRateNum >= 0.01) failReasons.push(`http_req_failed=${httpFailedRate}%`);
    if (!coreTransitionsOk) {
        failReasons.push(
            `핵심 상태 전이 실패(promotion_created=${created}, reject=${rejectSuccess}, accept=${acceptSuccess}, rank검증실패=${rankValidationFailed})`
        );
    }

    const lines = [
        '',
        '===== Waiting Queue / Promotion 시나리오 검증 결과 =====',
        `대기열 진입 요청 수                    : ${joinTotal}`,
        `대기열 진입 성공 (queue_join_success)   : ${joinSuccess}`,
        `대기열 진입 충돌 (queue_join_conflict)  : ${joinConflict}`,
        `대기열 순위 검증 실패                   : ${rankValidationFailed}`,
        `Promotion 생성/확인 건수 (promotion_created) : ${created}`,
        `Promotion accept 성공                   : ${acceptSuccess}`,
        `Promotion reject 성공                   : ${rejectSuccess}`,
        `예상 밖 응답 (join+promotion 합계)      : ${totalUnexpected}`,
        `요청 응답시간 avg / p95 / max (ms)      : ${avgDuration} / ${p95Duration} / ${maxDuration}`,
        `HTTP 요청 실패율 (http_req_failed)      : ${httpFailedRate}%`,
        `판정: ${pass ? 'PASS - 모든 상태 전이와 threshold를 충족함' : `FAIL - ${failReasons.join(', ')}`}`,
        '',
        '(참고) Grafana에서 WebSocket Notifications(gymflow_websocket_notification_sent_total)이',
        '이 실행 동안 함께 증가했는지 확인하면, 여기서 REST로 검증한 승급/거절/수락 각 시점에',
        '실제 STOMP 알림도 발송됐는지 교차검증할 수 있습니다.',
        '=========================================================',
        '',
    ];

    return {
        stdout: lines.join('\n'),
        'k6-waiting-queue-promotion-load-summary.json': JSON.stringify(data, null, 2),
    };
}

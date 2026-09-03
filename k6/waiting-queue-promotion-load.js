import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL;
if (!BASE_URL) {
    throw new Error(
        'BASE_URL 환경변수가 필요합니다. 예: k6 run -e BASE_URL=http://localhost:8080 k6/waiting-queue-promotion-load.js'
    );
}

const USER_COUNT = __ENV.USER_COUNT ? parseInt(__ENV.USER_COUNT, 10) : 20;
if (!Number.isInteger(USER_COUNT) || USER_COUNT < 2) {
    throw new Error(`USER_COUNT는 2 이상의 정수여야 합니다(1등 reject, 2등 accept 검증에 필요). 입력값: "${__ENV.USER_COUNT}"`);
}

const TEST_PASSWORD = 'K6-Load-Test-1234!';

const RUN_ID = Date.now();

const RUN_START_DAY_OFFSET = 1 + (RUN_ID % 14);

http.setResponseCallback(http.expectedStatuses(200, 201, 204, 409));

const queueJoinSuccess = new Counter('queue_join_success');
const queueJoinConflict = new Counter('queue_join_conflict');
const queueJoinUnexpected = new Counter('queue_join_unexpected');
const queueRankValidationFailed = new Counter('queue_rank_validation_failed');

const promotionCreated = new Counter('promotion_created');
const promotionAcceptSuccess = new Counter('promotion_accept_success');
const promotionRejectSuccess = new Counter('promotion_reject_success');
const promotionUnexpected = new Counter('promotion_unexpected');

const flowDuration = new Trend('flow_duration', true);

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
        promotion_created: ['count==1'],
        promotion_reject_success: ['count==1'],
        promotion_accept_success: ['count==1'],
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

export function setup() {
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

    const policy = activeResource.reservationPolicy;
    const duration = policy.maxDuration || policy.minDuration;

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

    const sortedForDebug = [...joined].sort((a, b) => a.waitingRank - b.waitingRank);
    console.log(`[phase-a-debug] 대기열 진입 성공 ${sortedForDebug.length}건 (waitingRank 오름차순):`);
    sortedForDebug.forEach((j) => {
        console.log(`  testUser=${j.testUser}, waitingQueueId=${j.waitingQueueId}, waitingRank=${j.waitingRank}`);
    });

    const waitingQueueIds = new Set(joined.map((j) => j.waitingQueueId));
    const waitingRanks = new Set(joined.map((j) => j.waitingRank));
    const maxRank = joined.length > 0 ? Math.max(...joined.map((j) => j.waitingRank)) : 0;

    const rankValid =
        waitingQueueIds.size === joined.length && waitingRanks.size === joined.length && maxRank === joined.length;

    if (!rankValid) {
        queueRankValidationFailed.add(1);

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

    joined.sort((a, b) => a.waitingRank - b.waitingRank);
    return joined;
}

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

export default function (data) {
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

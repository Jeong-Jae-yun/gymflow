# GymFlow Troubleshooting

## Document Information

| 항목 | 내용 |
|------|------|
| Document | Troubleshooting |
| Version | v1.0 |
| Author | 정재윤 |
| Last Updated | 2026-09-04 |

이 문서는 "왜 현재 구조가 필요하게 되었는가"를 다룬다. [04_Concurrency_Control.md](./04_Concurrency_Control.md)와 [05_Redis_Realtime.md](./05_Redis_Realtime.md)가 현재 구조를 설명한다면, 이 문서는 그 구조가 만들어지기 전의 문제, 원인, 해결 과정을 다룬다.

선정 기준: Drift 분석에서 발견된 여러 후보 중, 코드 diff·커밋 메시지·회귀 테스트가 함께 확인되는 근거가 가장 강한 사례만 선정했다. 문서 분량을 채우기 위해 근거가 약한 사례는 포함하지 않았다.

---

## A. WaitingQueue FIFO timestamp score collision

### 문제

동시에 대기열에 등록한 사용자들의 순번(rank)이 실제 요청 순서와 다르게 배정되거나 중복될 수 있었다.

### 기존 구현

`WaitingQueueRedisRepository.add()`는 등록 시각을 그대로 ZSET score로 사용했다.

```
double score = createdAt.toInstant(ZoneOffset.UTC).toEpochMilli();
redisTemplate.opsForZSet().add(key, String.valueOf(waitingQueueId), score);
```

### 원인 분석

밀리초 단위 타임스탬프를 score로 쓰면, 같은 밀리초에 도착한 두 요청이 동일한 score를 갖게 된다. ZSET은 score가 같으면 멤버 값(문자열) 기준으로 정렬하므로, 실제 도착 순서와 무관하게 순위가 결정될 수 있었다.

### 해결 과정

score를 요청 시각이 아니라 Resource+시간대별로 채번하는 단조 증가 정수(sequence)로 교체했다. `INCR`(채번) → `ZADD`(score=sequence) → `ZRANK`(현재 순위)를 하나의 Lua 스크립트로 원자 실행해, 등록과 순위 조회 사이에 다른 요청이 끼어들 수 없도록 했다.

### 최종 해결

```lua
local sequence = redis.call('INCR', KEYS[1])
redis.call('ZADD', KEYS[2], sequence, ARGV[1])
return redis.call('ZRANK', KEYS[2], ARGV[1])
```

자세한 구조는 [05_Redis_Realtime.md](./05_Redis_Realtime.md#3-waitingqueue-fifo)를 참고한다.

### 검증

- `WaitingQueueRedisRepositoryTest`: 20개 스레드가 동시에 `addAndGetRank()`를 호출해도 rank가 `{0..19}`로 정확히, 중복 없이 배정되는지 검증.
- `WaitingQueueConcurrencyTest`: 20명 동시 등록 시 `waitingRank`가 Redis 순서 및 실제 승급 순서와 일치하는지 검증.

### Trade-off / 배운 점

큐가 완전히 비면 `:sequence` 카운터 키도 함께 정리해야 하는 책임이 추가로 생겼다(`REMOVE_SCRIPT`가 `ZCARD == 0`일 때 `DEL`). 타임스탬프 기반 정렬은 직관적이지만, 밀리초 정밀도로는 실제 동시 요청의 순서를 구분할 수 없다는 것이 이 사례의 핵심 교훈이다.

---

## B. Reservation write-skew from exact-timestamp lock key

### 문제

겹치지만 시작/종료 시각이 정확히 같지는 않은 두 예약 요청이 동시에 성공할 수 있었다(예: 14:00~14:30과 14:20~14:35가 동시에 CONFIRMED).

### 기존 구현

```java
public static String from(Long resourceId, LocalDateTime startAt, LocalDateTime endAt) {
    return KEY_PREFIX + resourceId + ":" + startAt + ":" + endAt;
}
```

Lock Key가 요청의 정확한 시작/종료 시각 문자열이었다.

### 원인 분석

이 방식은 정확히 같은 시작/종료 시각의 요청만 같은 Key를 공유해 직렬화된다. 시간이 겹치기만 하고 시작/종료가 다른 두 요청은 서로 다른 Key를 얻어 병렬로 Lock을 통과할 수 있었다 — write-skew.

### 해결 과정

요청 구간 `[startAt, endAt)`을 절대 5분 grid 슬롯으로 분해해, 슬롯별로 개별 Lock을 모두 획득하는 방식으로 교체했다. 겹치는 두 시간 구간은 항상 최소 하나의 슬롯을 공유하도록 설계해, 겹치기만 해도 반드시 하나의 Lock을 두고 경쟁하게 만들었다.

### 최종 해결

`ReservationSlotLockKey.canonicalSlots()` + `ReservationSlotLockRepository.tryLockAll()`. 알고리즘 상세는 [04_Concurrency_Control.md](./04_Concurrency_Control.md#5-canonical-5-minute-slot-lock)를 참고한다.

### 검증

`ReservationConcurrencyTest.createReservation_WithConcurrentOverlappingButNotIdenticalRequests_ShouldSucceedOnlyOnce` — 14:00~14:30과 14:20~14:35처럼 겹치지만 동일하지 않은 두 요청을 동시에 보내 정확히 하나만 성공하는지 검증.

### Trade-off / 배운 점

슬롯 개수만큼 Redis 왕복이 필요해, 긴 예약일수록 Lock 획득에 필요한 요청 수가 늘어난다. 슬롯 일부 획득에 실패하면 이미 획득한 슬롯을 모두 롤백해야 하는 복잡도도 함께 생겼다. "Key를 요청 값 그대로 쓰면 안전할 것"이라는 직관이 실제로는 겹침(overlap)을 보호하지 못한다는 점이 핵심 교훈이다.

---

## C. Resource status change vs Reservation/Promotion write-skew

### 문제

관리자가 Resource를 점검(MAINTENANCE) 상태로 전환하기 직전 "현재 점유 없음"을 확인했더라도, 그 직후 새 예약이나 Promotion이 커밋되면 상태 변경이 성공한 뒤에도 Resource가 실제로는 점유된 상태로 남을 수 있었다.

### 기존 구현

관리자 상태 변경(점유 여부 조회 → 상태 변경)과 일반 예약 생성(`ReservationSlotLock` 기반)이 서로 다른 concurrency boundary를 사용했다. 이 시점의 설계 문서에는 다음과 같은 기록이 남아 있다.

> "관리자가 점유 없음을 확인한 직후 새 Reservation이 커밋되어 상태 변경이 성공한 뒤에도 Resource가 점유된 상태로 남는 write-skew가 이론적으로 가능하다. 이번 범위에서는 이 경합을 막는 별도의 Lock을 추가하지 않았다."

### 원인 분석

두 작업이 서로 다른 Lock을 사용하므로 서로를 직렬화하지 못하는 구조적 문제였다. 이는 사후에 발견된 버그가 아니라, 설계 단계에서 이미 인지하고 있었지만 별도 Lock 없이 남겨둔 위험이었다.

### 해결 과정

Resource 단위의 상위 Lock인 `ResourceAvailabilityLock`을 신설하고, 관리자 상태 변경과 신규 시간 점유를 만드는 모든 작업(예약 생성, Promotion ACCEPT, Promotion OFFERED 생성)이 이 Lock을 공통 concurrency boundary로 공유하도록 재작성했다.

### 최종 해결

같은 날 설계 문서도 함께 갱신되어, 위 인용문이 다음과 같이 바뀌었다.

> "MAINTENANCE/INACTIVE로의 실제 전환 시에는 이 Lock을 획득한 뒤 그 안에서 점유 여부를 검사하므로... write-skew는 발생하지 않는다."

상세 Lock 경계는 [04_Concurrency_Control.md](./04_Concurrency_Control.md#8-promotion--resource-state--waitingqueue-boundaries)를 참고한다.

### 검증

`AdminResourceConcurrencyTest`, `ResourceAvailabilityLockRepositoryTest` — 관리자 상태 변경과 예약 생성/Promotion이 동시에 발생해도 모순 상태(비활성 Resource에 점유 예약이 남는 상태)가 생기지 않는지 검증.

### Trade-off / 배운 점

"알고 있는 위험을 문서에 남기고 다음 단계에서 해결한다"는 방식으로 진행된 사례다. `ResourceAvailabilityLock`은 Resource 전체를 잠그는 coarse-grained Lock이라, 같은 Resource의 서로 다른 시간대 예약 생성 요청도 이 Lock을 두고 순서를 기다린다는 트레이드오프가 남아 있다([04장 10절](./04_Concurrency_Control.md#10-trade-off) 참고).

---

## D. Promotion follow-up self-contention and lock release timing

### 문제

대기열 승급 거절/만료/체크인 타임아웃 이후 다음 대기자에게 재승급을 시도하는 로직에 두 가지 문제가 얽혀 있었다. 자신이 방금 해제한 `PromotionLock`을 곧바로 같은 Key로 다시 획득해야 하는 self-invocation 구조 때문에, Lock을 트랜잭션 커밋 이후까지 유지하지 못하고 메서드 종료 즉시 해제할 수밖에 없었다. 그리고 이 즉시 해제 방식은 별도로, "Redis unlock 이후 아직 DB COMMIT 전"인 짧은 구간에 다른 요청이 같은 Lock을 재획득해 커밋되지 않은 변경을 보지 못한 채 검증을 통과할 수 있는 위험도 안고 있었다.

### 기존 구현

`PromotionService`의 상태 변경 메서드(`reject()` 등)가 자기 자신을 `@Transactional`로 두고, 메서드 종료 시 `finally`에서 즉시 Lock을 해제한 뒤 같은 메서드 안에서 후속 승급을 이어서 호출하는 구조였다.

### 원인 분석

같은 클래스 안에서 self-invocation으로 후속 승급을 호출하면, Spring 프록시를 거치지 않아 트랜잭션이 새로 열리지 않고 기존 트랜잭션에 이어지거나, Lock을 유지한 채 같은 Lock을 다시 요구하는 self-contention이 발생한다. `REQUIRES_NEW`로 새 트랜잭션을 강제로 열어 우회하는 방법도 검토했으나, `TransactionSynchronization.afterCompletion()` 콜백 내부에서 곧바로 `REQUIRES_NEW` 호출을 이어 붙이면 아직 완전히 정리되지 않은 이전 트랜잭션 리소스에 잘못 참여해 새 작업이 커밋되지 않는 문제를 실제로 겪었다(이 판단은 설계 문서에 남은 기록이며, 현재 코드에는 `REQUIRES_NEW` 사용 흔적이 남아있지 않다).

### 해결 과정

`@Lazy` self-proxy 패턴을 도입했다. `reject()`/`handleOfferedExpiration()`/`handleCheckInTimeout()`을 `@Transactional`이 아닌 순수 orchestrator로 바꾸고, `@Lazy` self 프록시를 통해 실제 상태 변경을 담당하는 `@Transactional` 메서드(`rejectTransactional()` 등)를 호출해 그 트랜잭션을 완전히 커밋까지 끝낸 뒤에야, 트랜잭션 밖에서 순수 Java 순차 호출로 `PromotionProcessor.tryPromote()`를 실행하도록 분리했다. 동시에 `TransactionAwareLockReleaser`를 도입해, 이 Lock을 포함한 모든 정합성 보호 Lock의 해제 시점을 `afterCompletion()`으로 통일했다.

### 최종 해결

구조는 [04_Concurrency_Control.md](./04_Concurrency_Control.md#3-global-lock-ordering)의 Lock Ordering, [6절](./04_Concurrency_Control.md#6-transactionawarelockreleaser)의 `TransactionAwareLockReleaser`와 동일하다.

### 검증

`PromotionFollowUpTransactionOrderingIntegrationTest`(self-contention이 남아있다면 후속 `tryPromote()`가 Lock 획득 실패로 조용히 skip되어 다음 대기자가 승급되지 않는다는 것을 검증), `LockTransactionCompletionIntegrationTest`(Lock 해제가 실제 트랜잭션 완료 이후인지 Testcontainers로 검증).

### Trade-off / 배운 점

`@Lazy` self-proxy는 Spring의 프록시 기반 트랜잭션 전파 메커니즘을 이해하지 못하면 왜 필요한지 파악하기 어려운 코드다. 이 프로젝트에서는 정합성을 위해 이 복잡도를 감수했지만, 새로 합류하는 개발자에게는 코드만으로 의도가 드러나지 않으므로 별도의 설명이 필요하다.

---

## E. WebSocket notification before DB commit

### 문제

대기열 승급 WebSocket 알림을 받은 프론트엔드가 곧바로 `GET /api/waiting-queues`를 재조회하면, 아직 커밋되지 않은 Promotion을 읽지 못해 `promotionId=null`로 응답받는 문제가 실제 통합 테스트 중 재현되었다.

### 기존 구현

`PromotionProcessor.tryPromote()`가 (1) `WaitingQueue`를 `PROMOTED`로 변경 → (2) `WaitingQueuePromotion` 저장 → (3) WebSocket 이벤트 발행을 순서대로 수행했는데, (3)이 트랜잭션 commit **이전에** 실행되었다.

### 원인 분석

Redis Pub/Sub 발행(`redisTemplate.convertAndSend()`)이 Spring 트랜잭션 경계와 무관하게 호출 즉시 실행되는 구조였다. 발행이 커밋보다 먼저 일어나면, 알림을 받은 클라이언트가 서버보다 먼저 "새 Promotion이 생겼다"는 사실을 알게 되어, 아직 존재하지 않는 데이터를 조회하는 순서 역전이 발생한다.

### 해결 과정

`WaitingQueueEventPublisher.publish()`를 트랜잭션 동기화를 확인해, 활성 트랜잭션이 있으면 `afterCommit()` 콜백 안에서만 실제 발행하도록 변경했다(활성 트랜잭션이 없는 경로는 즉시 발행). 수정 커밋 47초 뒤 같은 세션에서 회귀 테스트가 추가되었다.

### 최종 해결

```java
public void publish(WaitingQueuePromotedEvent event) {
    if (TransactionSynchronizationManager.isSynchronizationActive()
            && TransactionSynchronizationManager.isActualTransactionActive()) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                publishNow(event);
            }
        });
        return;
    }
    publishNow(event);
}
```

전체 흐름은 [05_Redis_Realtime.md](./05_Redis_Realtime.md#7-pubsub--websocket-bridge)를 참고한다.

### 검증

`PromotionWebSocketCommitOrderingIntegrationTest` — WebSocket 이벤트를 수신한 시점에 DB에서 이미 `promotionId`를 조회할 수 있는지 End-to-End로 검증한다. 테스트 클래스 Javadoc에 "실제 통합 테스트 중 재현된 race condition에 대한 회귀 테스트"라고 명시되어 있다.

### Trade-off / 배운 점

발행을 커밋 이후로 미루면서, 발행 자체의 실패는 더 이상 트랜잭션 롤백에 영향을 주지 않는다(장점). 반대로 "DB 커밋은 끝났지만 알림 발행만 실패하는" 상황이 이론적으로 가능해졌고, 이 경우 사용자는 실시간 알림 대신 REST 재조회로만 최신 상태를 확인하게 된다([05_Redis_Realtime.md](./05_Redis_Realtime.md#9-failure-strategy) Failure Strategy와 연결되는 지점이다). "커밋 이후에만 알린다"는 원칙은 알림의 정확성을 높이지만, 알림의 전달 자체를 보장하지는 않는다.

---

## Summary

| 사례 | 핵심 원인 | 해결 수단 |
|---|---|---|
| A | ZSET score로 밀리초 타임스탬프 사용 → 동시 요청 score 충돌 | Lua 기반 원자적 INCR+ZADD+ZRANK |
| B | Lock Key가 정확한 시각 문자열 → 겹치기만 하는 요청은 직렬화 안 됨 | 5분 canonical grid slot lock |
| C | 관리자 상태 변경과 예약 생성이 서로 다른 Lock 사용 | Resource 단위 상위 Lock(ResourceAvailabilityLock) 공유 |
| D | self-invocation으로 인한 Lock self-contention + 즉시 해제로 인한 commit-gap | `@Lazy` self-proxy 분리 + TransactionAwareLockReleaser |
| E | Pub/Sub 발행이 트랜잭션 커밋보다 먼저 실행됨 | afterCommit 콜백 이후에만 발행 |

---


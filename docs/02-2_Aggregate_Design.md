# Aggregate Design

## 1. Overview

본 문서는 GymFlow의 Aggregate를 정의하고 각 Aggregate의 책임과 경계를 명확하게 기술한다.

Aggregate는 DDD(Domain Driven Design)의 핵심 개념으로, 함께 변경되어야 하는 객체를 하나의 단위로 묶어 일관성을 유지하기 위한 설계 단위이다.

GymFlow는 Aggregate 간 결합도를 최소화하고, 각 Aggregate가 자신의 비즈니스 규칙만 책임지는 구조를 목표로 한다.

---

## 2. Aggregate Design Principles

GymFlow는 다음 원칙을 기반으로 Aggregate를 설계한다.

### 2.1 Single Responsibility

각 Aggregate는 하나의 비즈니스 책임만 가진다.

예를 들어 Reservation은 예약을 관리하며,
Resource의 상세 정보를 수정하지 않는다.

---

### 2.2 Aggregate Independence

Aggregate는 다른 Aggregate의 내부 상태를 직접 변경하지 않는다.

Aggregate 간 협력은 Application Service 또는 Domain Event를 통해 이루어진다.

---

### 2.3 Consistency Boundary

하나의 트랜잭션에서는 하나의 Aggregate만 일관성을 보장한다.

Aggregate 간 데이터 변경은 이벤트 기반으로 처리한다.

---

### 2.4 Resource First

예약 시스템의 모든 비즈니스는 Resource를 중심으로 동작한다.

Reservation은 Machine을 직접 참조하지 않는다.

---

## 3. Resource Aggregate

### Responsibility

Resource Aggregate는 예약 가능한 모든 대상(Resource)을 관리한다.

예약 대상의 상태, 운영시간, 위치 등을 관리하며
예약 자체는 Reservation Aggregate가 담당한다.

---

### Aggregate Root

```
Resource
```

---

### Entity

```
ReservationPolicy
```

ReservationPolicy는 Resource의 예약 정책(slotDuration, minDuration, maxDuration)을 관리하는 내부 Entity이다.

독립적으로 존재할 수 없으며 반드시 하나의 Resource에 종속된다(1:1).

---

### Value Object

```
Location

OperatingHours
```

---

### Business Rules

- Resource는 예약 가능한 대상이어야 한다.
- 점검 중인 Resource는 예약할 수 없다.
- 비활성화된 Resource는 예약 대상이 아니다.
- 운영시간 외에는 예약할 수 없다.
- Resource 상태 변경은 Reservation 상태를 직접 변경하지 않는다.
- Resource는 ADMIN만 관리할 수 있다(`/api/admin/resources/**`). ADMIN 권한 검사는 `SecurityConfig`의 `.requestMatchers("/api/admin/**").hasRole("ADMIN")` URL 정책으로 중앙화되어 있으며, Controller 메서드에는 별도의 `@PreAuthorize`를 붙이지 않는다. 생성(`POST`)은 Resource와 ReservationPolicy를 하나의 트랜잭션으로 함께 만들며, 초기 status는 클라이언트 입력과 무관하게 서버에서 ACTIVE로 고정한다. `type`은 생성 시 필수이며 생성 이후에는 변경할 수 없다(`PUT`의 수정 대상은 name/capacity/description/ReservationPolicy로 한정되고, status는 별도의 `PATCH .../status` API로만 변경한다).
- 물리 DELETE API는 두지 않으며, INACTIVE 상태를 삭제를 대체하는 상태로 사용한다.
- ReservationPolicy(slotDuration/minDuration/maxDuration)는 `maxDuration >= minDuration`, `minDuration % slotDuration == 0`, `maxDuration % slotDuration == 0`을 만족해야 하며(위반 시 400 `INVALID_RESERVATION_POLICY`), 이 조합 검증은 Service 레벨에서 수행한다(Entity 자체는 각 값의 양수 여부와 `minDuration <= maxDuration`만 보장한다). ReservationPolicy를 변경해도 이미 생성된 Reservation/WaitingQueue/OFFERED Promotion의 startAt/endAt은 소급 변경되지 않으며, 변경된 정책은 이후 신규 Reservation 생성과 기존 CHECKED_IN Reservation의 연장 요청부터 적용된다.
- Resource를 MAINTENANCE 또는 INACTIVE로 전환하려면, 현재/미래 점유 Reservation(`CONFIRMED`/`CHECKED_IN` 중 `endAt > now`)과 활성 `WaitingQueuePromotion`(`OFFERED` 중 `expiresAt > now`)이 존재하지 않아야 한다. 존재하면 409 `RESOURCE_STATUS_CHANGE_NOT_ALLOWED`로 거부되며, 동일한 상태로의 변경 요청은 이 검사 없이 idempotent하게 성공한다. ACTIVE로의 전환은 점유 충돌이 발생할 수 없으므로 이 검사 대상이 아니다.
- Resource 수정 및 상태 변경 성공 시 `ResourceCacheRepository.evict()`로 Redis Resource Cache를 무효화한다. Redis 장애로 evict가 실패해도 MySQL 변경 사항은 유지되는 Fail-Open으로 처리하며, 예외를 상위로 전파하지 않는다(생성은 기존 캐시 엔트리가 없으므로 evict 대상이 아니다).
- 관리자 상태 변경(점유 여부 조회 → 상태 변경)과 새로운 시간 점유권을 만드는 작업(예약 생성, Promotion ACCEPT, Promotion OFFERED 생성)은 Resource 단위 상위 Lock인 `ResourceAvailabilityLock`(`gymflow:lock:resource-availability:{resourceId}`) concurrency boundary를 공유한다. MAINTENANCE/INACTIVE로의 실제 전환 시에는 이 Lock을 획득한 뒤 그 안에서 점유 여부를 검사하므로, 관리자가 점유 없음을 확인한 직후 새 Reservation/Promotion이 끼어들어 상태 변경이 성공한 뒤에도 Resource가 점유된 상태로 남는 write-skew는 발생하지 않는다. 전역 Lock 획득 순서는 `ResourceAvailabilityLock → PromotionLock → ReservationSlotLock`으로 고정된다.
- MySQL 정합성을 보호하는 `ResourceAvailabilityLock`/`ReservationSlotLock`/`PromotionLock`/`WaitingQueueRegistrationLock`은 Service 메서드가 반환되는 시점이 아니라, 그 메서드를 감싼 Spring transaction이 실제로 COMMIT 또는 ROLLBACK을 완료한 시점(`TransactionSynchronization.afterCompletion`)에 해제한다(`TransactionAwareLockReleaser`). Service 메서드 반환 직후 Redis unlock, 그 뒤에야 실제 DB COMMIT이 일어나는 순서였다면, 그 사이의 짧은 구간에 다른 요청이 같은 Lock을 다시 획득해 아직 commit되지 않은 변경을 보지 못한 채 동일한 검증을 통과할 수 있었다. transaction synchronization이 활성화되지 않은 실행 경로(예: 실제 Spring 컨텍스트 없이 Service를 직접 호출하는 단위 테스트)에서는 이 방식을 쓸 수 없으므로 기존과 동일하게 Service 메서드의 `finally`에서 즉시 해제한다.
- WaitingQueue 자동 재승급(`PromotionProcessor.tryPromote()`)은 별도 Bean으로 분리되어 있다. `PromotionService.reject()`/`handleOfferedExpiration()`/`handleCheckInTimeout()`는 자신은 `@Transactional`이 아닌 순수 orchestrator이며, `@Lazy` self-proxy 참조(`self`)를 통해 실제 상태 변경 + `PromotionLock` 해제를 담당하는 `@Transactional` 메서드(`rejectTransactional()` 등)를 호출해 그 transaction을 COMMIT까지 완전히 끝낸 뒤에야, 완전히 새로운 transaction에서 `PromotionProcessor.tryPromote()`를 호출해 다음 대기자 승급을 시도한다. 상태 변경 메서드가 예외를 던지면(rollback) 이 호출 자체가 일어나지 않으므로 후속 승급도 실행되지 않는다. 이 구조 덕분에 이전에 남아 있던 "자신의 `PromotionLock`을 해제한 직후 같은 Key를 self-invocation으로 다시 획득해야 해서 즉시 해제를 유지할 수밖에 없었던" self-contention 문제와 commit-gap이 모두 제거되었다(`PromotionLock`도 다른 Lock과 동일하게 transaction completion까지 유지된다). 새 transaction을 시작할 때 `REQUIRES_NEW` propagation은 쓰지 않는다 — `TransactionSynchronization.afterCompletion()` 콜백 내부에서 곧바로 `REQUIRED` 호출을 이어 붙이면 아직 완전히 정리되지 않은 이전 transaction 리소스에 잘못 참여해 새 작업이 commit되지 않을 수 있음을 실제로 확인했으므로, 후속 승급 호출은 반드시 원인 transaction이 proxy 밖으로 완전히 반환된 뒤(순수 Java 순차 호출)에 이루어진다.

---

### Aggregate Structure

```
Resource (Aggregate Root)

│

├── Location (VO)

├── OperatingHours (VO)

└── ReservationPolicy (Entity)
```

---

## 4. Reservation Aggregate

### Responsibility

Reservation Aggregate는 예약의 전체 생명주기를 관리한다.

예약 생성부터 취소, 체크인, 체크아웃, 완료까지의 모든 상태 전이를 담당한다.

---

### Aggregate Root

```
Reservation
```

---

### Entity

없음

Reservation Aggregate는 Aggregate Root 하나만으로 구성한다.

---

### Value Object

없음 (현재 미사용)

현재 구현에서는 예약 시작 시간과 종료 시간을 TimeSlot Value Object로 감싸지 않고,
Reservation Aggregate Root의 startAt / endAt 필드로 직접 관리한다.

비고: 향후 예약 연장, 슬롯 단위 검증 등 시간 관련 도메인 규칙이 복잡해지면
TimeSlot Value Object로 리팩터링할 수 있다.

---

### Business Rules

- 동일한 Resource는 동일한 시간에 점유 상태(CONFIRMED, CHECKED_IN)의 예약이 하나만 존재할 수 있다.
- Resource의 예약 시간 충돌은 CONFIRMED 및 CHECKED_IN 상태의 Reservation을 모두 점유 상태로 간주하여 판단한다. CANCELLED, NO_SHOW, COMPLETED 상태의 Reservation은 충돌 검사 대상에서 제외된다.
- 시간 구간이 정확히 맞닿는 예약(예: 14:00~14:15와 14:15~14:30)은 충돌로 보지 않는다.
- `PromotionStatus.OFFERED`는 해당 Resource/시간 구간에 대한 임시 예약 우선권으로 취급한다. 일반 예약 생성 및 연장은 동일 Resource의 Active OFFERED Promotion과 시간 구간이 겹칠 경우 허용하지 않으며(`RESERVATION_PROMOTION_RESERVED`), 판단 기준은 Reservation 충돌 조건과 동일하게 `existing.startAt < requestedEndAt && existing.endAt > requestedStartAt`을 사용한다(`endAt == requestedStartAt`인 인접 시간대는 허용). `WaitingQueuePromotionRepository.existsOverlapping()`이 이 조회를 담당하며, `ACCEPTED`/`REJECTED`/`EXPIRED`로 전이된 Promotion은 더 이상 임시 우선권으로 간주하지 않는다. 예약 연장은 새로 점유하는 구간(`oldEndAt ~ newEndAt`)만을 기준으로 이 overlap을 재검증한다.
- Resource 시간 점유권을 획득/확장하는 모든 작업(예약 생성, 예약 연장, Promotion ACCEPT, Promotion OFFERED 생성)은 공통 `ReservationSlotLock` 경계를 공유한다. 요청 구간 `[startAt, endAt)`을 절대 5분 grid 슬롯(`gymflow:lock:reservation-slot:{resourceId}:{slotStart}`)들로 분해해 슬롯별로 Lock을 모두 획득해야 하며, 겹치는 두 시간 구간은 항상 최소 하나의 슬롯을 공유하도록 보장된다. 이전에는 `{resourceId}:{startAt}:{endAt}`을 그대로 Lock Key로 사용했으나, 이는 정확히 같은 시작/종료 시각의 요청만 직렬화할 뿐 겹치기만 하는 서로 다른 요청은 직렬화하지 못하는 한계(write-skew)가 있어 이 방식으로 대체되었다. 예약 연장은 새로 점유하는 구간(`oldEndAt ~ newEndAt`)만 Lock하며, 이미 점유 중인 `startAt ~ oldEndAt` 구간은 커밋된 상태에 대한 overlap 재검증만으로 충분히 보호된다.
- 예약 생성 시 Resource는 예약 가능 상태여야 한다.
- 예약 시간은 운영시간 내에 존재해야 한다.
- 체크인은 예약 시작 시각(startAt) 5분 전부터 startAt까지만 가능하다. startAt까지 체크인하지 않으면 예약은 NO_SHOW 처리된다.
- 체크아웃 이후 예약은 COMPLETED 상태가 된다.
- 예약 완료 후 UsageHistory 생성 이벤트를 발행한다.
- 예약 연장은 다음 예약이 존재하지 않을 경우에만 가능하다.

---

### Reservation Lifecycle

정상 흐름
```
CONFIRMED

↓

CHECKED_IN

↓

COMPLETED
```

예외 흐름

```
CONFIRMED

↓

CANCELLED

또는

CONFIRMED

↓

NO_SHOW
```

NO_SHOW는 체크인 허용 시간(startAt - 5분 ~ startAt) 내에 체크인하지 않고 startAt이 지난 경우의 상태이다.

COMPLETED는 체크인 후 정상적으로 체크아웃을 완료한 경우의 상태이다.

---

### Aggregate Structure

```
Reservation (Aggregate Root)

├── id
├── reservationBatchId
├── user        (참조 — User Aggregate Root)
├── resource    (참조 — Resource Aggregate Root)
├── startAt
├── endAt
└── status
```

Reservation은 user와 resource 필드를 통해 User Aggregate와 Resource Aggregate를 참조하지만,
User와 Resource는 각각 독립적인 Aggregate Root이며 Reservation Aggregate에 속한 내부 Entity가 아니다.

reservationBatchId는 UUID이며, 하나의 예약 요청으로 생성된 여러 Reservation이
동일한 값을 공유할 수 있도록 UNIQUE 제약을 두지 않는다.

---
## 5. WaitingQueue Aggregate

### Responsibility

WaitingQueue Aggregate는 예약이 불가능한 경우 사용자의 대기열을 관리한다.

예약과는 독립적으로 동작하며, 예약 취소 또는 만료 시 다음 대기 사용자를 승급시키는 책임을 가진다.

---

### Aggregate Root

```
WaitingQueue
```

---

### Entity

없음

WaitingQueue Aggregate는 Aggregate Root 하나만으로 구성한다. 사용자의 대기 요청 자체가 WaitingQueue Entity(Aggregate Root)이며, 별도의 하위 Entity를 두지 않는다.

---

### Value Object

없음

---

### Business Rules

- 하나의 사용자 대기 요청마다 하나의 WaitingQueue Entity가 생성된다. 동일 Resource/시간대에 여러 사용자의 WaitingQueue가 존재할 수 있다.
- 동일한 사용자는 같은 WaitingQueue에 중복 등록할 수 없다. 이 중복 등록 검사와 저장 사이의 동시성 경합(같은 사용자가 동일 Resource/시간대에 동시에 여러 번 요청하는 경우)은 전용 Redis Lock(`gymflow:lock:waiting-queue:{userId}:{resourceId}:{startAt}:{endAt}`, `WaitingQueueRegistrationLock`)으로 직렬화한다. 이 Lock은 `ReservationSlotLock`과 달리 Resource 시간 점유권이 아니라 "같은 사용자의 같은 등록 요청" 단위를 보호하므로, 서로 다른 사용자의 동일 시간대 대기열 등록이나 같은 사용자의 다른 시간대 등록은 서로 직렬화되지 않는다. WAITING 상태만 중복 등록 차단 대상이며, CANCELLED 이력이 있어도 동일 시간대 재등록은 허용되므로 DB UNIQUE 제약 대신 Lock + WAITING 상태 재조회 조합을 사용한다.
- 대기 순서는 Redis Sorted Set(ZSET)을 통해 관리한다.
- CONFIRMED → NO_SHOW 또는 CONFIRMED → CANCELLED로 빈 슬롯이 발생하면 가장 앞의 WaitingQueue가 승급(PROMOTED) 대상이 된다.
- WaitingQueue의 PROMOTED는 "예약 우선 기회를 받음"을 의미할 뿐 예약 생성 완료를 의미하지 않는다. 실제 수락/거절/응답 timeout은 별도의 WaitingQueuePromotion Aggregate가 관리한다.

---

### Aggregate Structure

```
WaitingQueue (Aggregate Root)

├── user        (참조 — User Aggregate Root)
├── resource    (참조 — Resource Aggregate Root)
├── startAt
├── endAt
└── status
```

---

### WaitingQueuePromotion (별도 Aggregate)

WaitingQueue의 승급 기회(OFFERED)를 수락(ACCEPTED)/거절(REJECTED)/응답 시간 만료(EXPIRED)로 관리하는 별도 Aggregate Root이다.

- OFFERED 상태에만 Redis TTL(기본 최대 2분, `endAt - now - ReservationPolicy.minDuration`으로 상한을 재계산)이 걸리며, TTL 만료 시 EXPIRED로 전이되고 다음 대기자에게 자동으로 재승급을 시도한다.
- ACCEPTED 시 신규 Reservation(CONFIRMED)이 생성되고 WaitingQueuePromotion과 1:1로 연결되며(`reservation_id`), 별도의 1분 Redis Check-In TTL이 부여된다. 그 안에 체크인하지 않으면 Reservation은 CANCELLED + `PROMOTION_CHECKIN_TIMEOUT` 사유로 취소되고 다음 대기자 승급이 다시 시도된다.
- Promotion으로 생성된 Reservation의 체크인 가능 시간은 일반 예약(`startAt - 5분 ~ startAt`)과 다르다. Promotion ACCEPT는 startAt이 이미 지난 뒤에도 발생할 수 있으므로, 체크인 가능 시간은 **Promotion ACCEPT 시각(`acceptedAt`)부터 1분**으로 별도 계산한다(`Reservation.checkInByPromotion(now, deadline)`). ReservationService는 체크인 대상 Reservation이 ACCEPTED 상태의 WaitingQueuePromotion과 연결되어 있는지(`WaitingQueuePromotionRepository.findByReservationId`)로 두 정책을 구분하며, 일반 checkIn()의 시간창은 이 경우에도 변경되지 않는다.
- 동일 Resource에서 시간 구간이 겹치는 Active OFFERED Promotion은 동시에 존재할 수 없다. `tryPromote()`는 새 OFFERED를 생성하기 전 `WaitingQueuePromotionRepository.existsOverlapping()`으로 동일 Resource의 겹치는 Active OFFERED 존재 여부를 재검증하며(정확히 같은 시작/종료 시각이 아니어도 겹치기만 하면 차단), `ACCEPTED`/`REJECTED`/`EXPIRED` 상태의 Promotion은 이 검사 대상에서 제외된다. Promotion ACCEPT는 자기 자신의 OFFERED를 Reservation으로 전환하는 과정이므로 이 overlap 검사를 별도로 수행하지 않는다(자기 자신 때문에 차단되지 않음).
- 동일 Resource + 시간 구간에 동시에 여러 OFFERED가 생성되지 않도록 전용 Redis Lock(`gymflow:lock:promotion:{resourceId}:{startAt}:{endAt}`, `PromotionLock`)으로 직렬화한다. 이 Lock은 Promotion 체크인과 Check-In TTL 만료 처리 사이의 경쟁도 동일하게 직렬화한다. `PromotionLock`은 Promotion 상태 전이(OFFERED/ACCEPTED/REJECTED/EXPIRED)만 보호하는 책임이며, Resource 시간 점유권 자체는 보호하지 않는다.
- Promotion ACCEPT(신규 Reservation 생성)와 자동 OFFERED 생성은 Resource 시간 점유권을 변경하는 작업이므로, 일반 예약 생성/연장과 동일한 `ReservationSlotLock` 경계도 함께 사용하며, 그 바깥쪽으로 Resource 단위 상위 Lock인 `ResourceAvailabilityLock`도 함께 사용한다. 세 Lock을 모두 사용하는 경로(ACCEPT, 자동 승급)는 항상 `ResourceAvailabilityLock → PromotionLock → ReservationSlotLock` 순서로 획득하며(해제는 역순), 프로젝트 전체에서 이 순서가 뒤바뀌는 경로는 없다(Deadlock 방지). `tryPromote()`는 `ResourceAvailabilityLock` 획득 이후 Resource 상태가 ACTIVE인지도 함께 재검증하여, Resource가 이미 비활성 상태로 전환된 뒤에는 새 OFFERED가 생성되지 않도록 한다. 이렇게 겹치는 시간대의 일반 예약 생성과 Promotion OFFERED 생성/ACCEPT가 동시에 발생해도 `ReservationSlotLock`의 슬롯 경합으로 인해 둘 중 하나만 성립하며, 관리자의 Resource 상태 변경과 동시에 발생해도 `ResourceAvailabilityLock`으로 인해 Resource가 MAINTENANCE/INACTIVE인 동시에 새 CONFIRMED Reservation 또는 활성 OFFERED Promotion이 존재하는 모순 상태는 발생하지 않는다.
- Promotion 체크인이 성공하면 Check-In TTL Key를 즉시 삭제해 불필요한 만료 이벤트가 발생하지 않도록 한다(Redis 삭제 실패는 fail-open으로 처리하고 MySQL CHECKED_IN 상태는 그대로 유지한다).
- deadline을 넘긴 체크인 요청은 서버 오류가 아니라 `PROMOTION_CHECKIN_EXPIRED`(409 Conflict) Business Error로 응답한다. Service가 deadline을 먼저 검증해 API 레벨의 의미 있는 오류를 반환하고, `Reservation.checkInByPromotion()`의 동일한 시간 검증은 최종 방어선으로 유지한다.
- MySQL이 Source of Truth이며 Redis는 순번(ZSET)·TTL·Lock·Pub/Sub 등 보조 수단으로만 사용한다.

---

## 6. Favorite Aggregate

### Responsibility

Favorite Aggregate는 사용자의 즐겨찾기(Resource Bookmark)를 관리한다.

예약과는 완전히 독립적으로 동작한다.

---

### Aggregate Root

```
Favorite
```

---

### Entity

없음

---

### Value Object

없음

---

### Business Rules

- 하나의 사용자는 동일한 Resource를 한 번만 즐겨찾기할 수 있다.
- Resource가 삭제되어도 Favorite는 직접 삭제하지 않는다.
- Favorite는 예약 가능 여부와 관계없이 등록할 수 있다.

---

### Aggregate Structure

```
Favorite (Aggregate Root)
```

---

## 7. UsageHistory Aggregate

### Responsibility

UsageHistory Aggregate는 완료된 예약을 기반으로 이용 이력을 저장한다.

조회와 통계 분석을 위한 Aggregate이며 예약 프로세스와는 독립적으로 관리된다.

---

### Aggregate Root

```
UsageHistory
```

---

### Entity

없음

---

### Value Object

없음

---

### Business Rules

- Reservation이 Completed 상태가 되면 생성된다.
- 생성 이후 수정하지 않는다.
- 삭제하지 않는다.
- 통계 및 Ranking의 데이터로 활용된다.
- UsageHistory는 통계의 Source of Truth다. 통계 계산 시 ReservationStatus를 다시 JOIN해서 COMPLETED 여부를 재검증하지 않으며, DB에 존재하는 UsageHistory 자체를 실제 사용 완료 기록으로 간주한다.
- Resource의 상태(ACTIVE/MAINTENANCE/INACTIVE)와 무관하게 과거 UsageHistory 조회/통계에서 제외하지 않는다. Resource를 물리 삭제하지 않고 INACTIVE로 유지하는 이유 중 하나가 이 참조 무결성 및 통계 보존이다.
- UsageHistory에는 Resource 이름 등의 snapshot 컬럼을 두지 않는다. 조회 응답의 Resource name/type/status는 현재 Resource Entity를 JOIN해서 가져오므로, 관리자가 Resource 이름을 변경하면 과거 UsageHistory 조회에서도 현재 이름이 노출된다.
- 사용자는 `GET /api/usage-histories`(목록, 기간/페이지네이션 지원)와 `GET /api/usage-histories/statistics`(내 통계)로 본인의 이용 이력만 조회할 수 있으며, userId를 query parameter로 받지 않고 JWT 인증 정보에서 추출한다. 관리자는 `GET /api/admin/resources/{resourceId}/statistics`로 특정 Resource의 누적 이용 통계를 조회한다(별도의 AdminUsageHistoryController 없이 기존 `AdminResourceController`에 위치).
- 모든 조회/통계의 기간 필터는 `startedAt` 기준 반개구간 `[from, to)`로 통일한다(`from`은 포함, `to`는 제외). `from`/`to`가 모두 있을 때 `from >= to`이면 400 `INVALID_DATE_RANGE`로 거부된다. `endedAt`이 기간을 넘어가는 세션도 월/기간 경계로 분할하지 않고 `startedAt`이 속한 기간에 duration 전체를 귀속시킨다.
- 총 이용 횟수/시간, Resource별 통계는 애플리케이션에서 List를 순회하지 않고 MySQL의 COUNT/SUM/GROUP BY로 계산한다(0건일 때 SUM은 COALESCE로 0 처리).
- 조회 패턴(`user_id + started_at`, `resource_id + started_at`)에 맞춰 `usage_histories` 테이블에 복합 인덱스 `idx_usage_history_user_started(user_id, started_at)`, `idx_usage_history_resource_started(resource_id, started_at)`를 둔다. 기존 `reservation_id` UNIQUE 제약은 그대로 유지된다.

---

### Aggregate Structure

```
UsageHistory (Aggregate Root)
```

---

## 8. Aggregate Reference Rules

Aggregate는 서로의 내부 상태를 직접 변경하지 않는다.

참조는 Aggregate Root를 통해서만 수행한다.

### Allowed References

```
Reservation
        │
        ├──▶ User Aggregate
        │
        └──▶ Resource Aggregate

Favorite
        │
        ▼
    Resource

Favorite
        │
        ▼
      User
```

Reservation은 User와 Resource를 각각 Aggregate Root 단위로만 참조하며,
두 Aggregate의 내부 상태를 직접 변경하지 않는다.

---

### Not Allowed

```
Reservation

↓

UsageHistory
```

Reservation은 UsageHistory를 생성하지 않는다.

ReservationCompleted Domain Event를 통해 생성된다.

---

```
WaitingQueue

↓

Reservation 수정
```

WaitingQueue는 Reservation을 직접 수정하지 않는다.

Application Service가 승급을 수행한다.

---

## 9. Domain Service

Entity 하나의 책임으로 표현하기 어려운 비즈니스 규칙은 Domain Service에서 관리한다.

### ReservationPolicyService

책임

- 예약 가능 여부 판단
- 운영시간 검증
- 점검 여부 확인

입력

```
Resource

Reservation

OperatingHours
```

출력

```
예약 가능 여부
```

---

### ReservationExtensionPolicy

책임

- 예약 연장 가능 여부 판단

검증 항목

- 다음 예약 존재 여부
- 최대 연장 횟수
- 운영시간 초과 여부

---

### AvailabilityPolicy

책임

Resource의 현재 예약 가능 여부를 계산한다.

검증 항목

- 현재 상태
- 점검 여부
- 운영시간
- 예약 존재 여부

---

### RankingPolicy

책임

Reservation 생성 성공 시 Resource별 점수를 Redis Sorted Set(ZSET, `gymflow:ranking:resource:reservation`)에
누적한다. UsageHistory(실제 사용 완료 기록)를 기반으로 계산하지 않으며, 예약 취소/NO_SHOW/체크아웃 시
별도의 감소나 재계산도 수행하지 않는다.

Resource 상태가 MAINTENANCE/INACTIVE로 바뀌어도 ZSET의 score와 membership은 그대로 유지된다(원본
Ranking은 상태와 무관하게 보존). 반면 사용자에게 노출하는 Ranking(`GET /api/resources/rankings`,
`GET /api/resources/{resourceId}/ranking`)은 ACTIVE Resource만 필터링한 뒤 1부터 시작하는 연속 순위를
다시 매긴다. `ResourceService`가 Redis raw ranking을 offset 0부터 20개 단위(`RANKING_SCAN_BATCH_SIZE`)로
순차 조회하면서 MySQL에서 ACTIVE 여부를 확인하는 방식으로 이 재번호를 수행하며, MySQL에 존재하지 않는
stale Redis ID는 조회를 실패시키지 않고 건너뛴다. Redis 조회가 배치 도중 실패하면 이미 모은 부분 결과를
버리고 Ranking 전체를 unavailable로 취급하는 Fail-Open으로 처리한다(TOP N은 빈 목록, 특정 Resource는
rank=null/score=0). Resource가 매우 많고 상위 raw ranking 대부분이 비활성 상태라면 특정 Resource의
ACTIVE 순위를 계산하기 위해 다수의 batch를 scan해야 할 수 있다는 known scalability limitation이 있으며,
현재 MVP 범위에서는 이를 허용하고 향후 ACTIVE 전용 별도 ZSET 등으로 개선할 수 있다.

---

## 10. Domain Event

Aggregate 간 직접 의존성을 줄이기 위해 Domain Event를 사용한다.

Domain Event는 비즈니스적으로 의미 있는 상태 변경을 표현한다.

---

### ReservationCreated

발생 시점

예약 생성 완료

후속 작업

- Redis Cache 갱신
- WebSocket Broadcast

---

### ReservationCancelled

발생 시점

예약 취소

후속 작업

- WaitingQueue 승급
- Resource 상태 변경
- WebSocket Broadcast

---

### ReservationExtended

발생 시점

예약 연장 성공

후속 작업

- Redis Cache 갱신
- WebSocket Broadcast

---

### ReservationExpired

발생 시점

TTL 만료

후속 작업

- 자동 취소
- WaitingQueue 승급

---

### CheckInCompleted

발생 시점

체크인 성공

후속 작업

- Resource 상태 변경
- WebSocket Broadcast

---

### CheckOutCompleted

발생 시점

체크아웃 성공

후속 작업

- Reservation 완료
- UsageHistory 생성

---

### ResourceStatusChanged

발생 시점

상태 변경

후속 작업

- Redis Cache 갱신
- WebSocket Broadcast

---

### WaitingQueuePromoted

발생 시점

대기열 승급

후속 작업

- 예약 생성
- 사용자 알림

---

## 11. Repository Design Principles

Aggregate마다 하나의 Repository를 둔다.

Repository는 Aggregate Root만 관리한다.

### Repository 목록

```
ResourceRepository

ReservationRepository

WaitingQueueRepository

WaitingQueuePromotionRepository

FavoriteRepository

UsageHistoryRepository

UserRepository
```

ReservationPolicyRepository는 별도로 생성하지 않는다.

ReservationPolicy는 Resource Aggregate 내부 Entity이므로 Resource를 통해서만 저장/조회된다.

Repository는 다음 책임만 가진다.

- Aggregate 저장
- Aggregate 조회
- Aggregate 삭제

비즈니스 로직은 Repository에 포함하지 않는다.

---

## 12. Value Object

GymFlow는 변경 불가능한 값을 Value Object로 표현한다.

### TimeSlot

```
startTime

endTime
```

예약 시간 표현

비고: 현재 Reservation Aggregate는 TimeSlot을 사용하지 않고
startAt / endAt 필드로 직접 시간을 관리한다.

---

### Location

```
floor

zone

position
```

Resource 위치 표현

예시

```
3F

Weight Zone

A-12
```

---

### OperatingHours

```
openTime

closeTime
```

운영시간 표현

---

Value Object는 식별자를 가지지 않으며 불변 객체로 설계한다.

---

## 13. Enum

### ResourceStatus

```
ACTIVE

INACTIVE

MAINTENANCE
```

ResourceStatus는 Resource 자체의 운영 상태만 표현한다.

`IN_USE`(실시간 점유 여부)는 Resource의 운영 상태가 아니라
특정 Time Slot의 예약 점유 결과이므로 Reservation Aggregate에서 판단한다.

따라서 Resource는 다음 세 가지 운영 상태만 가진다.

- ACTIVE : 예약 가능한 정상 운영 상태
- INACTIVE : 운영하지 않는 상태 (예약 대상 아님)
- MAINTENANCE : 점검 중인 상태 (예약 대상 아님)

---

### ReservationStatus

```
CONFIRMED

CHECKED_IN

COMPLETED

CANCELLED

NO_SHOW
```

---

### ResourceType

```
MACHINE

PT_ROOM

LOCKER

STRETCH_ZONE

SAUNA

SHOWER_ROOM
```

02-1 Domain Modeling 문서 1.1에서 예약 가능한 대상으로 제시한
Sauna와 Shower Room을 ResourceType에 포함한다.

---

### UserRole

```
USER

ADMIN
```

---

모든 Enum은 데이터베이스에 문자열(String) 형태로 저장한다.

Enum의 순서 변경에 따른 문제를 방지하고 가독성을 높이기 위함이다.

---

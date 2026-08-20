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
MachineProfile
```

MachineProfile은 Resource의 상세 정보를 관리하는 내부 Entity이다.

독립적으로 존재할 수 없으며 반드시 하나의 Resource에 종속된다.

Reservation은 MachineProfile을 직접 참조하지 않는다.

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

---

### Aggregate Structure

```
Resource (Aggregate Root)

│

├── Location (VO)

├── OperatingHours (VO)

├── ReservationPolicy (Entity)

└── MachineProfile (Entity)
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

- 동일한 Resource는 동일한 시간에 CONFIRMED 상태의 예약이 하나만 존재할 수 있다.
- 예약 충돌 검사는 CONFIRMED 상태의 Reservation만을 대상으로 한다. CHECKED_IN 상태의 Reservation은 충돌 검사 대상에서 제외되며, 시간 구간이 종료된 CHECKED_IN 예약은 다음 시간대 예약을 막지 않는다.
- 시간 구간이 정확히 맞닿는 예약(예: 14:00~14:15와 14:15~14:30)은 충돌로 보지 않는다.
- 예약 생성 시 Resource는 예약 가능 상태여야 한다.
- 예약 시간은 운영시간 내에 존재해야 한다.
- 예약 시작 시각(startAt)부터 5분 이내에 체크인하지 않으면 예약은 NO_SHOW 처리된다.
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

NO_SHOW는 예약 시작 시각(startAt)부터 5분의 유예시간 내에 체크인하지 않은 경우의 상태이다.

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

```
QueueItem
```

QueueItem은 대기열에 등록된 사용자의 정보를 관리하는 내부 Entity이다.

QueueItem은 WaitingQueue 외부에서 독립적으로 존재할 수 없다.

---

### Value Object

없음

---

### Business Rules

- 하나의 Resource와 TimeSlot마다 하나의 WaitingQueue가 존재한다.
- 동일한 사용자는 같은 WaitingQueue에 중복 등록할 수 없다.
- QueueItem은 등록 순서를 유지한다.
- 예약 취소 또는 만료 시 가장 앞의 QueueItem이 승급 대상이 된다.
- 승급 대상이 예약을 수락하지 않으면 다음 QueueItem으로 넘어간다.

---

### Aggregate Structure

```
WaitingQueue (Aggregate Root)

│

└── QueueItem (Entity)
```

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

MachineProfile
```

Reservation은 MachineProfile을 직접 참조하지 않는다.

---

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

UsageHistory를 기반으로 인기 Resource 점수를 계산한다.

계산 결과는 Redis Sorted Set에 저장된다.

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

FavoriteRepository

UsageHistoryRepository

UserRepository
```

MachineProfileRepository와 QueueItemRepository는 생성하지 않는다.

두 Entity는 Aggregate 내부에서만 관리된다.

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

### MachineType

```
CHEST

BACK

LEG

SHOULDER

CARDIO

ARM

CORE
```

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

# Next Document

➡️ **02-3_ERD_Design.md**

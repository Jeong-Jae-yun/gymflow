# GymFlow

# Document Information

| 항목 | 내용              |
|------|-----------------|
| Document | Domain Modeling |
| Version | v1.0            |
| Author | 정재윤             |
| Last Updated | 2026-08-26      |

---

# 1. Domain Overview

## 1.1 Domain 정의

GymFlow는 헬스장의 다양한 시설(Resource)을 예약하고 관리하는
**실시간 Resource Reservation Platform**이다.

예약 대상은 운동기구만을 의미하지 않는다.

GymFlow에서는 예약 가능한 모든 대상을 **Resource**라는 하나의 개념으로 정의한다.

예를 들어 다음과 같은 대상들이 모두 Resource가 될 수 있다.

- Machine
- PT Room
- Locker
- Stretch Zone
- Sauna
- Shower Room

예약 시스템은 Resource라는 추상 개념만 이해하며,
Resource 타입별 구분은 ResourceType Enum으로, 예약 정책은 ReservationPolicy Entity로 관리한다.

---

## 1.2 Domain Goal

GymFlow가 해결하려는 핵심 문제는 다음과 같다.

- 동일 시간대 중복 예약 방지
- 실시간 상태 동기화
- 예약 이후 체크인 관리
- 예약 연장
- 대기열 관리
- Resource 사용률 분석
- 향후 새로운 Resource 확장

---

# 2. Architecture Decision Record (ADR)

## ADR-001

### 왜 Machine이 아니라 Resource인가?

### Problem

운동기구 중심으로 설계하면
예약 시스템이 운동기구에 강하게 결합된다.

예를 들어

Machine

↓

Reservation

이 구조에서는

PT Room이 추가될 경우

Reservation까지 수정해야 한다.

---

### Decision

예약 가능한 모든 대상을

Resource

로 추상화한다.

Reservation은

Machine이 아니라

Resource를 참조한다.

---

### Consequence

장점

- 예약 로직 재사용
- 새로운 Resource 추가 가능
- OCP 만족
- 높은 확장성

단점

- 초기 설계가 다소 복잡하다.

---

# 3. Domain Modeling Principles

GymFlow는 다음 원칙을 따른다.

## 3.1 Resource First

예약은 운동기구를 예약하는 것이 아니라

Resource를 예약하는 것이다.

Machine은 Resource의 한 종류이다.

---

## 3.2 Reservation 중심

GymFlow의 핵심 비즈니스 흐름은 Reservation을 중심으로 발생한다.

Reservation은 단순한 예약 정보가 아니라,
사용자가 특정 Resource를 특정 Time Slot 동안 사용할 권리를 의미한다.

전체 흐름은 다음과 같다.

Resource 조회

↓

Reservation 생성

↓

체크인

↓

Resource 사용

↓

체크아웃

↓

UsageHistory 생성


Reservation은 다음 정보를 가진다.

- 예약 대상 Resource
- 예약 Time Slot
- 예약 사용자
- 예약 상태
- 체크인 정보
- 사용 이력 연결 정보

또한 하나의 운동 세션에서 여러 Resource Slot을 예약하는 경우,
동일한 reservation_batch_id를 통해 생성된 Reservation들을
하나의 예약 요청 단위로 식별할 수 있다.

reservation_batch_id는 현재 단순 그룹 식별자로 사용하며,
향후 ReservationGroup Aggregate로 확장 가능하다.

여러 Reservation이 하나의 reservation_batch_id를 공유해야 하므로,
reservation_batch_id에는 UNIQUE 제약을 두지 않는다.
---

## 3.3 상태(State) 중심 모델링

GymFlow에서는 Resource 상태와 Reservation 상태를 분리하여 관리한다.

Resource 상태:

ACTIVE

↓

MAINTENANCE

↓

ACTIVE


또는

ACTIVE

↓

INACTIVE


Resource 상태는 운영 상태만 표현한다.

특정 시간대의 점유 여부는 Resource 상태가 아니라
Reservation이 판단한다.

관리자는 `PATCH /api/admin/resources/{resourceId}/status`로 ACTIVE/MAINTENANCE/INACTIVE 세 상태를
자유롭게 전환할 수 있다. 다만 MAINTENANCE 또는 INACTIVE로 전환하려면 해당 Resource에 현재/미래
점유 Reservation(`CONFIRMED`/`CHECKED_IN` 중 `endAt > now`)과 활성 `WaitingQueuePromotion`(`OFFERED`
중 `expiresAt > now`)이 존재하지 않아야 하며, 존재하면 409 `RESOURCE_STATUS_CHANGE_NOT_ALLOWED`로
거부된다. 동일한 상태로의 변경 요청은 충돌 검사 없이 idempotent하게 성공한다. GymFlow는 별도의
Resource 물리 삭제 API를 두지 않으며, INACTIVE 상태를 삭제를 대체하는 상태로 사용한다.


Reservation 상태:

정상 흐름

CONFIRMED

↓

CHECKED_IN

↓

COMPLETED


예외 흐름

CONFIRMED

↓

CANCELLED

또는

CONFIRMED

↓

NO_SHOW


NO_SHOW는 체크인 허용 시간(startAt - 5분 ~ startAt) 내에 체크인하지 않고 startAt이 지난 경우의 상태이다.

COMPLETED는 체크인 후 정상적으로 체크아웃을 완료한 경우의 상태이다.

---

## 3.4 실시간(Event Driven)

예약

체크인

취소

연장

대기열 승급

등은 모두 Event로 취급한다.

이벤트는 WebSocket과 Redis Pub/Sub를 통해 전파된다.

---

# 4. Ubiquitous Language

| 용어                | 설명                            |
|-------------------|-------------------------------|
| Resource          | 예약 가능한 모든 대상                  |
| Machine           | 운동기구 Resource                 |
| Reservation       | Resource 예약                   |
| Reservation Slot  | Reservation이 점유한 특정 Time Slot |
| Waiting Queue     | 예약 대기열                        |
| Waiting Queue Promotion | 대기열 사용자에게 제공되는 예약 우선 기회(OFFERED) |
| Check In          | 예약 시작 확인                      |
| Check Out         | 이용 종료                         |
| Favorite          | 즐겨찾기                          |
| Usage History     | 이용 기록                         |
| Maintenance       | 점검 상태                         |
| Availability      | 예약 가능 여부                      |
| Time Slot         | Resource 예약 가능한 최소 시간 단위      |
| Domain Event      | 도메인 상태 변경 이벤트                 |
| Reservation Batch | 하나의 예약 요청으로 생성된 Reservation 그룹 식별자  |



---

# 5. Core Domain

GymFlow의 Core Domain은

Resource Reservation이다.

```
                 Resource Reservation

                         |
        ---------------------------------

        Resource        Reservation       Waiting Queue

                         |

                     Check In

                         |

                  Usage History
```

예약이 서비스의 중심이다.

---

# 6. Supporting Domain

Core Domain을 지원하는 기능

- Authentication
- Favorite
- Notification
- Statistics
- Ranking

이들은 핵심 예약 로직을 보조한다.

---

# 7. Generic Domain

여러 프로젝트에서 공통적으로 사용하는 기능

- JWT
- Security
- Logging
- Monitoring
- Exception
- API Response
- Validation

---

# 8. Bounded Context

GymFlow는 비즈니스 책임을 기준으로 다음과 같은 Bounded Context로 구성한다.

각 Context는 자신의 도메인 모델과 비즈니스 규칙을 관리하며,
다른 Context와는 최소한의 의존성을 유지한다.

---

## Resource Context

### 책임

- Resource 생성 및 관리
- Resource 상태 관리
- 운영시간 관리
- 점검(Maintenance) 관리

### 포함

- Resource (Aggregate Root)
- ReservationPolicy (Entity)
- OperatingHours (Value Object)
- Location (Value Object)

---

## Reservation Context

### 책임

- 예약 생성
- 예약 취소
- 예약 연장
- 체크인
- 체크아웃
- 예약 상태 관리

### 포함

- Reservation (Aggregate Root)

현재 구현에서는 예약 시작 시간과 종료 시간을 TimeSlot Value Object로 감싸지 않고
Reservation의 startAt / endAt 필드로 직접 관리한다.

---

## Waiting Queue Context

### 책임

- 대기열 생성
- 대기열 등록
- 대기 순번 관리
- 예약 승급

### 포함

- WaitingQueue (Aggregate Root)
- WaitingQueuePromotion (Aggregate Root, 별도)

WaitingQueue는 QueueItem 없이 사용자별 대기 요청 자체를 Aggregate Root로 저장한다.

예약 승급은 WaitingQueue가 직접 수행하지 않고, 별도의 WaitingQueuePromotion Aggregate가
OFFERED 상태를 생성하여 사용자에게 예약 기회를 제공하는 방식으로 이루어진다.

---

## User Context

### 책임

- 회원 관리
- 인증 및 인가
- 사용자 권한 관리
- 즐겨찾기 관리

### 포함

- User (Aggregate Root)
- Favorite (Aggregate Root)

---

## Statistics Context

### 책임

- Resource 사용률 분석
- 인기 Resource Ranking
- 이용 이력 관리
- 통계 데이터 생성

### 포함

- UsageHistory (Aggregate Root)

UsageHistory는 통계의 Source of Truth이며, 사용자는 `GET /api/usage-histories`(내 이용 이력)와
`GET /api/usage-histories/statistics`(내 이용 통계)로, 관리자는
`GET /api/admin/resources/{resourceId}/statistics`로 특정 Resource의 누적 이용 통계를 조회한다.
모든 기간 필터는 `startedAt` 기준 `[from, to)` 반개구간이며, 통계는 MySQL COUNT/SUM/GROUP BY로 계산한다.

인기 Resource Ranking은 UsageHistory 통계와 의미가 다른 별개의 지표다. Ranking의 score는 예약 생성
성공 횟수를 Redis Sorted Set(ZSET)에 누적한 값이며, UsageHistory 기반 통계처럼 실제 사용 완료 여부를
반영하지 않는다. 사용자는 `GET /api/resources/rankings`(TOP N)와 `GET /api/resources/{resourceId}/ranking`
(특정 Resource)로 조회하며, 두 지표는 서로 혼합해서 계산하지 않는다.

---

# 9. Context Mapping

```
User

↓

Reservation

↓

Resource

↓

Usage History

↓

Statistics
```

또한

Reservation

↓

Waiting Queue

↓

Notification

으로도 연결된다.

---

# 10. Domain Relationship

```
                         User
                          │
              ┌───────────┴───────────┐
              │                       │
         Favorite               Reservation
                                      │
                                      ▼
                                  Resource
                                      │
                                      ▼
                              ReservationPolicy

WaitingQueue
     │
     ▼
WaitingQueuePromotion

UsageHistory
```

### Aggregate 관계

- Reservation은 Resource를 참조하며, Resource 하위에 별도의 Profile Entity를 두지 않는다.
- ReservationPolicy는 Resource Aggregate 내부 Entity이다.
- WaitingQueue는 Reservation과 독립적으로 관리되는 Aggregate이다.
- WaitingQueue가 승급되면 WaitingQueuePromotion(OFFERED)이 생성되어 사용자에게 예약 기회를 제공하며, 사용자가 수락(ACCEPTED)해야 비로소 Reservation이 생성된다.
- UsageHistory는 Reservation 완료 이벤트를 통해 생성된다.
- Favorite는 User와 Resource를 연결하지만 예약과는 독립적으로 동작한다.

---

# 11. Resource Model

Resource는 GymFlow에서 예약 가능한 모든 대상을 의미하는 Aggregate Root이다.

예약 시스템은 Resource만을 대상으로 동작하며,
Resource 타입별 세부 구분은 ResourceType Enum으로, 예약 정책은 ReservationPolicy Entity로 관리한다.

```
                Resource (Aggregate Root)

──────────────────────────────────────

id

name

type

status

location

operatingHours

──────────────────────────────────────
            1 : 1
──────────────────────────────────────

ReservationPolicy (Entity)

slotDuration

minDuration

maxDuration
```

### Resource의 책임

- 예약 가능한 대상 관리
- 현재 상태 관리
- 운영시간 관리
- 위치 관리

### ReservationPolicy의 책임

- Resource별 예약 슬롯 단위(slotDuration) 관리
- 최소/최대 이용 시간(minDuration/maxDuration) 관리

ReservationPolicy는 독립적인 예약 대상이 아니며,
반드시 하나의 Resource에 종속된다(1:1).

Resource는 `POST /api/admin/resources`로 ReservationPolicy와 함께 생성되며, `type`은 생성 시 필수이고
생성 이후에는 변경할 수 없다(`PUT /api/admin/resources/{resourceId}`의 수정 대상은 name/capacity/
description/ReservationPolicy로 한정된다). 관리자가 ReservationPolicy(slotDuration/minDuration/
maxDuration)를 변경해도 이미 생성된 Reservation/WaitingQueue/OFFERED Promotion의 startAt/endAt은
소급 변경되지 않으며, 변경된 정책은 이후 신규 Reservation 생성과 기존 CHECKED_IN Reservation의 연장
요청부터 적용된다. Resource 수정 및 상태 변경 성공 시에는 `ResourceCacheRepository.evict()`로 Redis
Resource Cache를 무효화하며, Redis 장애 시에도 MySQL 변경은 유지되는 Fail-Open으로 처리한다.

Reservation은 Resource 하위에 별도의 Profile Entity를 두지 않고
항상 Resource만 참조한다.

이를 통해 새로운 Resource 타입이 추가되어도
예약 로직은 변경되지 않는다.

---

### Resource 대표 이미지 (imageKey)

Resource는 모든 ResourceType(MACHINE/PT_ROOM/LOCKER/STRETCH_ZONE/SAUNA/SHOWER_ROOM)이 공통으로 사용할
수 있는 대표 이미지 1장을 가질 수 있다. MachineProfile 등 특정 타입 전용 Entity를 두지 않고, Resource에
`imageKey`(`VARCHAR(500)`, nullable) 컬럼으로 직접 관리한다.

`imageKey`에는 S3 전체 URL이나 Presigned URL이 아니라 `resources/{resourceId}/{UUID}.{extension}` 형식의
S3 Object Key만 저장한다(예: `resources/17/550e8400-e29b-41d4-a716-446655440000.webp`). URL은 DB에
저장하지 않으며, 조회 시점마다 `imageKey`를 기반으로 1시간 유효한 Presigned GET URL을 새로 생성해
`imageUrl`로 응답한다. `imageKey`가 `null`이면 `imageUrl`도 `null`이며, 이 경우 Frontend가 기본
이미지를 표시한다. 이미지 업로드/교체/삭제는 `PUT /DELETE /api/admin/resources/{resourceId}/image`
전용 API로만 이루어지며, Resource 생성/수정 API의 payload에는 이미지 관련 필드를 포함하지 않는다.

---

# 12. Future Expansion

새로운 Resource 타입은 별도의 Profile Entity를 추가하는 방식이 아니라,
ResourceType Enum에 값을 추가하는 방식으로 확장한다.

Resource

↓

ResourceType (MACHINE, PT_ROOM, LOCKER, STRETCH_ZONE, SAUNA, SHOWER_ROOM)

예약 시스템은 ResourceType의 구체적인 값을 알지 못하고 Resource만 참조하므로,
새로운 ResourceType이 추가되어도 예약 로직은 변경되지 않는다.

---

# 13. 설계 원칙

GymFlow는 다음 원칙을 따른다.

- Resource 중심 설계
- 예약과 Resource의 책임 분리
- 상태 기반 모델링
- Event 기반 실시간 처리
- 확장 가능한 ResourceType 구조
- 새로운 Resource 추가 시 예약 로직 변경 최소화
- Aggregate는 다른 Aggregate의 내부 상태를 직접 변경하지 않는다. 
- Aggregate 간 협력은 Domain Event 또는 Application Service를 통해 수행한다.

---

# 14. 다음 문서

➡️ **02-2_Aggregate_Design.md**

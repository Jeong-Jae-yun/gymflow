# GymFlow

# Document Information

| 항목 | 내용              |
|------|-----------------|
| Document | Domain Modeling |
| Version | v1.0            |
| Author | 정재윤             |
| Last Updated | 2026-08-12      |

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
각 Resource의 세부 정보는 별도의 Profile이 관리한다.

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

또는

CHECKED_IN

↓

EXPIRED


NO_SHOW는 예약 시작 시각(startAt)부터 5분의 유예시간 내에 체크인하지 않은 경우의 상태이다.

EXPIRED는 체크인은 했지만 예약 종료 시각(endAt)까지 체크아웃하지 않은 경우의 상태이다.

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
| Resource Profile  | Resource의 상세 정보               |
| Reservation       | Resource 예약                   |
| Reservation Slot  | Reservation이 점유한 특정 Time Slot |
| Waiting Queue     | 예약 대기열                        |
| Check In          | 예약 시작 확인                      |
| Check Out         | 이용 종료                         |
| Favorite          | 즐겨찾기                          |
| Usage History     | 이용 기록                         |
| Maintenance       | 점검 상태                         |
| Availability      | 예약 가능 여부                      |
| Queue Item        | 대기열 사용자                       |
| Time Slot         | Resource 예약 가능한 최소 시간 단위      |
| Profile           | Resource의 상세 정보               |
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
- MachineProfile (Entity)
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
- QueueItem (Entity)

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
                                 ╱        ╲
                                ▼          ▼
                  ReservationPolicy   MachineProfile

WaitingQueue
     │
     ▼
 QueueItem

UsageHistory
```

### Aggregate 관계

- Reservation은 Resource를 참조하지만 MachineProfile은 직접 참조하지 않는다.
- MachineProfile은 Resource Aggregate 내부 Entity이다.
- WaitingQueue는 Reservation과 독립적으로 관리되는 Aggregate이다.
- UsageHistory는 Reservation 완료 이벤트를 통해 생성된다.
- Favorite는 User와 Resource를 연결하지만 예약과는 독립적으로 동작한다.

---

# 11. Resource Model

Resource는 GymFlow에서 예약 가능한 모든 대상을 의미하는 Aggregate Root이다.

예약 시스템은 Resource만을 대상으로 동작하며,
각 Resource의 상세 정보는 Profile Entity가 관리한다.

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

MachineProfile (Entity)

manufacturer

machineType

serialNumber

description
```

### Resource의 책임

- 예약 가능한 대상 관리
- 현재 상태 관리
- 운영시간 관리
- 위치 관리

### MachineProfile의 책임

- 운동기구의 상세 정보 관리
- 제조사 정보
- 운동기구 종류
- 모델 정보
- 설명

MachineProfile은 독립적인 예약 대상이 아니며,
반드시 하나의 Resource에 종속된다.

Reservation은 MachineProfile을 직접 참조하지 않고
항상 Resource만 참조한다.

이를 통해 새로운 Resource 타입이 추가되어도
예약 로직은 변경되지 않는다.

향후에는 동일한 구조로 다음 Profile을 추가할 수 있다.

- MachineProfile
- PTRoomProfile
- LockerProfile
- StretchZoneProfile
- SaunaProfile

---

# 12. Future Expansion

현재

Resource

↓

MachineProfile

향후

Resource

↓

MachineProfile

↓

PTProfile

↓

LockerProfile

↓

StretchZoneProfile

↓

SaunaProfile

예약 시스템은 변경되지 않는다.

Profile만 추가된다.

---

# 13. 설계 원칙

GymFlow는 다음 원칙을 따른다.

- Resource 중심 설계
- 예약과 Resource의 책임 분리
- 상태 기반 모델링
- Event 기반 실시간 처리
- 확장 가능한 Profile 구조
- 새로운 Resource 추가 시 예약 로직 변경 최소화
- Aggregate는 다른 Aggregate의 내부 상태를 직접 변경하지 않는다. 
- Aggregate 간 협력은 Domain Event 또는 Application Service를 통해 수행한다.

---

# 14. 다음 문서

➡️ **02-2_Aggregate_Design.md**

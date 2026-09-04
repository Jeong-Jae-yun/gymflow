# GymFlow

> 실시간 Resource Reservation Platform

---

## Document Information

| 항목 | 내용               |
|------|------------------|
| Document | Project Overview |
| Version | v1.0             |
| Author | 정재윤              |
| Last Updated | 2026-09-04       |
---

# 1. 프로젝트 소개

## 프로젝트 개요

GymFlow는 헬스장의 운동기구 및 다양한 시설(Resource)을 실시간으로 예약하고 관리할 수 있는 플랫폼이다.

단순한 운동기구 예약 서비스가 아닌 **Resource Reservation Platform**이라는 추상화된 개념을 기반으로 설계하여 다양한 예약 대상(Resource)을 하나의 예약 시스템으로 관리하는 것을 목표로 한다.

사용자는 운동기구의 현재 상태를 실시간으로 확인하고 예약, 체크인, 체크아웃, 예약 연장, 대기열 등록 등을 수행할 수 있으며, 관리자는 리소스 관리와 운영 정책을 설정할 수 있다.

Redis 기반의 분산 락을 활용하여 동시 예약 문제를 해결하고, 대기열 승급(Promotion) 발생 시 Redis Pub/Sub와 WebSocket(STOMP)을 통해 해당 사용자에게 실시간으로 알림을 전달한다.

또한 Prometheus와 Grafana를 통해 서비스의 운영 상태를 모니터링하며, GitHub Actions와 AWS를 이용한 CI/CD 환경을 구축하여 실제 서비스와 유사한 운영 환경을 경험하는 것을 목표로 한다.

---

# 2. 프로젝트 목표

GymFlow는 단순 CRUD 프로젝트가 아니라 실제 서비스를 개발한다는 관점에서 다음과 같은 역량을 경험하고 증명하는 것을 목표로 한다.

## Backend

- 객체지향 설계
- DDD 기반 도메인 모델링
- Spring Boot 기반 REST API
- Spring Security + JWT 인증
- JPA 기반 데이터 관리

---

## Realtime

- WebSocket(STOMP)
- 대기열 승급(Promotion) 실시간 알림
- Redis Pub/Sub 기반 이벤트 전파

---

## Redis

- Distributed Lock
- Cache
- TTL
- Pub/Sub
- Sorted Set

---

## Monitoring

- Spring Boot Actuator
- Micrometer
- Prometheus
- Grafana

---

## Infrastructure

- Docker
- Docker Compose
- AWS EC2
- AWS RDS
- AWS S3
- Nginx
- GitHub Actions

---

## Testing

- JUnit5
- Testcontainers
- k6

---

# 3. 핵심 가치

GymFlow는 다음 다섯 가지 문제를 해결하는 것을 목표로 한다.

## 1. 동시 예약 문제 해결

여러 사용자가 동일한 Resource를 동시에 예약하는 상황에서 중복 예약이 발생하지 않도록 동일 시간대 중복 예약을 방지하고, 겹치는 시간 구간에 대한 동시성 제어를 Redis 분산 락과 DB 재검증으로 보장한다. 상세한 Lock 종류/키 구조/트랜잭션 경계는 [04_Concurrency_Control.md](./04_Concurrency_Control.md)에서 다룬다.

---

## 2. 실시간 상태 제공

예약이 불가능해 대기열에 등록한 사용자는, 앞선 예약이 취소/노쇼되어 승급(Promotion) 기회가 생기면 Redis Pub/Sub와 WebSocket(STOMP)을 통해 실시간으로 알림을 받는다. GymFlow에서 WebSocket으로 전파되는 이벤트는 이 대기열 승급 알림이 유일하다. 상세 흐름은 [05_Redis_Realtime.md](./05_Redis_Realtime.md)에서 다룬다.

---

## 3. 자동 예약 관리

체크인은 예약 시작 시각 5분 전부터 시작 시각까지만 가능하며, 그때까지 체크인하지 않으면 NO_SHOW 처리하여 리소스 회전율을 높인다.

Redis TTL을 이용하여 별도의 Polling 없이 자동 만료를 처리한다.

---

## 4. 인기 Resource 분석

예약 생성 성공 횟수를 기반으로 인기 순위를 제공한다. UsageHistory(실제 사용 완료 기록, MySQL Source of Truth) 기반 통계와는 별개의 지표다.

Redis Sorted Set을 활용하여 실시간 랭킹을 관리하며, `GET /api/resources/rankings`(TOP N)와 `GET /api/resources/{resourceId}/ranking`(특정 Resource)로 조회한다. 랭킹 산출 방식과 Redis 장애 시 처리 전략은 [05_Redis_Realtime.md](./05_Redis_Realtime.md)에서 다룬다.

---

## 5. 확장 가능한 Resource 구조

운동기구만 예약할 수 있는 시스템이 아니라 다양한 시설을 하나의 예약 시스템으로 관리할 수 있도록 Resource 중심으로 설계한다.

예를 들어 다음과 같은 Resource가 추가될 수 있다.

- Machine
- PT Room
- Locker
- Stretch Zone
- Sauna

예약 시스템은 Resource만 알고 있기 때문에 새로운 Resource가 추가되어도 예약 로직은 변경되지 않는다.

---

# 4. 왜 Resource 중심으로 설계하는가?

일반적인 예약 프로젝트는 다음과 같이 특정 도메인에 강하게 결합된다.

```
Machine

↓

Reservation
```

이 구조에서는 새로운 예약 대상이 추가될 때마다 예약 로직을 수정해야 한다.

GymFlow는 다음과 같은 구조를 목표로 한다.

```
Resource

├── Machine

├── PT Room

├── Locker

├── Stretch Zone

└── Sauna
```

Reservation은 Resource만 참조하기 때문에 새로운 Resource가 추가되더라도 예약 시스템은 변경되지 않는다.

이를 통해 Open-Closed Principle(OCP)을 만족하는 확장 가능한 구조를 지향한다.

---

# 5. 주요 기능

## 사용자 기능

### 인증

- 회원가입
- 로그인
- JWT 인증

---

### Resource

- Resource 목록 조회
- Resource 상세 조회
- 실시간 상태 조회
- 예약 가능 시간 조회
- Resource 대표 이미지 조회 (`imageUrl`, Private S3 + Presigned GET URL 1시간 유효, `imageKey`가 없으면 `null`)

---

### 예약

- 예약 생성
- 예약 취소
- 예약 연장
- 내 예약 조회

---

### 이용

- 체크인
- 체크아웃
- 내 이용 이력 조회(기간 필터, Pagination)
- 내 이용 통계 조회(총 이용 횟수/시간, Resource별 통계)

---

### 대기열

- 대기열 등록
- 자동 예약 승급

---

### 개인화

- 즐겨찾기
- 인기 운동기구 조회
- 인기 Resource TOP N 조회, 특정 Resource Ranking 조회

---

### 실시간

- 대기열 승급(Promotion) 실시간 알림

---

## 관리자 기능

### Resource 관리

- 등록 (Resource + ReservationPolicy 동시 생성, 초기 상태는 ACTIVE로 서버에서 고정)
- 수정 (name/capacity/description/ReservationPolicy, type과 status는 별도 정책으로 관리)
- 삭제 (물리 삭제 없이 INACTIVE 상태로 대체)
- 점검 모드 (ACTIVE ↔ MAINTENANCE ↔ INACTIVE 상태 변경, 활성 예약/승급 제안이 있으면 차단)
- 예약 제한
- 대표 이미지 업로드/교체/삭제 (`PUT`/`DELETE /api/admin/resources/{resourceId}/image`, JPEG/PNG/WebP만 허용, 최대 5MB, AWS SDK Default Credentials Provider Chain으로 EC2에서는 IAM Role(`GymFlowEC2Role`)을 통해 인증하며 Access Key를 코드/설정에 두지 않는다)

---

### 통계

- 사용률
- 예약률
- 인기 Resource
- 이용 시간 분석
- 특정 Resource의 누적 이용 통계 조회(`GET /api/admin/resources/{resourceId}/statistics`)

---

# 6. 기능 요구사항

| ID | 요구사항 |
|-----|----------|
| FR-001 | 회원은 로그인할 수 있다. |
| FR-002 | Resource를 조회할 수 있다. |
| FR-003 | 예약할 수 있다. |
| FR-004 | 동일 시간에는 하나만 예약 가능하다. |
| FR-005 | 예약 시간은 중복될 수 없다. |
| FR-006 | 예약자는 체크인할 수 있다. |
| FR-007 | 예약 시작 시각(startAt) 5분 전부터 startAt까지 체크인할 수 있으며, 그때까지 체크인하지 않으면 NO_SHOW 처리된다. |
| FR-008 | 체크아웃 시 Resource는 즉시 사용 가능 상태가 된다. |
| FR-009 | 예약 연장이 가능하다. |
| FR-010 | 대기열 등록이 가능하다. |
| FR-011 | 예약 취소 시 대기열이 자동 승급된다. |
| FR-012 | 관리자는 Resource를 관리한다. |
| FR-013 | 대기열 승급 시 해당 사용자에게 실시간으로 알림이 전달된다. |
| FR-014 | 자주 조회되는 데이터는 Redis Cache를 사용한다. |
| FR-015 | 동시 예약은 Redis Distributed Lock으로 제어한다. |
| FR-016 | 인기 Resource Ranking을 제공한다. |

---

# 7. 비기능 요구사항

## 성능 (초기 비기능 목표)

- 예약 API 평균 응답 시간 200ms 이하
- Resource 조회 API 평균 응답 시간 100ms 이하

위 수치는 설계 초기에 설정한 목표치이며, 실제 k6 부하 테스트 측정 결과는 [07_Test_Performance.md](./07_Test_Performance.md)에서 별도로 다룬다.

---

## 동시성

- 동일 Resource에 대한 중복 예약은 발생하지 않는다.

---

## 실시간성

- 대기열 승급 이벤트는 해당 사용자에게 즉시 전달된다.

---

## 확장성

새로운 Resource 타입이 추가되어도 예약 로직은 변경되지 않는다.

---

## 운영성

Prometheus와 Grafana를 이용하여 API 응답시간, 예약/락/대기열 지표, JVM/DB Connection Pool 상태 등을 모니터링한다. 상세 Dashboard 구성은 [06_Deployment_Monitoring.md](./06_Deployment_Monitoring.md)에서 다룬다.

---

# 8. 사용자 시나리오

## 시나리오 1 : 예약

```
로그인

↓

Resource 조회

↓

예약 가능 시간 선택

↓

예약 요청

↓

예약 성공
```

---

## 시나리오 2 : 체크인

```
예약 시작

↓

체크인

↓

CHECKED_IN

↓

체크아웃

↓

COMPLETED
```

---

## 시나리오 3 : No Show

```
예약

↓

체크인 안함

↓

TTL 만료

↓

NO_SHOW 처리

↓

대기열 승급
```

---

## 시나리오 4 : 예약 연장

```
운동 중

↓

연장 요청

↓

다음 예약 확인

↓

가능

↓

예약 연장
```

---

## 시나리오 5 : 대기열

```
예약 실패

↓

대기열 등록

↓

예약 취소 발생

↓

자동 승급

↓

실시간 알림
```

---

# 9. 비즈니스 규칙

## 예약

- 동일 Resource는 동일 시간에 하나의 예약만 가능하다.
- 점검(MAINTENANCE) 또는 비활성(INACTIVE) 상태의 Resource는 예약할 수 없다.

---

## 체크인

- 체크인은 예약 시작 시각(startAt) 5분 전부터 startAt까지만 가능하다.
- startAt까지 체크인하지 않으면 예약은 NO_SHOW 처리된다.
- 단, 대기열 승급(Promotion)으로 생성된 예약은 startAt이 이미 지난 뒤에 만들어질 수 있으므로 위 시간창을 적용하지 않는다. 이 경우 체크인 가능 시간은 Promotion 수락(ACCEPT) 시각부터 1분이며, 그 안에 체크인하지 않으면 예약은 취소되고 다음 대기자에게 승급 기회가 다시 주어진다.

---

## 체크아웃

- 체크아웃 즉시 Resource는 사용 가능 상태가 된다.
- 체크아웃하면 예약은 COMPLETED 상태가 된다.
- 이용 기록은 통계 데이터로 저장된다.

---

## 예약 연장

- 다음 예약이 없어야 한다.
- 최대 이용 시간을 초과할 수 없다.

---

## 대기열

- FIFO 정책을 사용한다.
- 예약 취소 시 가장 앞의 사용자가 자동 승급된다.

---

# 10. 프로젝트 차별화 포인트

- Resource 중심의 확장 가능한 도메인 설계
- Redis 기반 분산 락을 활용한 동시성 제어
- Redis Pub/Sub + WebSocket(STOMP) 기반 대기열 승급 실시간 알림
- Redis TTL을 활용한 자동 NO_SHOW 처리
- Redis Sorted Set 기반 인기 Resource 랭킹
- Prometheus + Grafana 기반 운영 모니터링
- GitHub Actions를 활용한 CI/CD
- AWS 기반 실제 운영 환경 구축

---

# Next Document

➡️ [02_Domain_Design.md](./02_Domain_Design.md)

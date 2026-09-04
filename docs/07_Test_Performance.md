# GymFlow Test & Performance

## Document Information

| 항목 | 내용 |
|------|------|
| Document | Test & Performance |
| Version | v1.0 |
| Author | 정재윤 |
| Last Updated | 2026-09-04 |

이 문서는 GymFlow의 모든 테스트/성능 검증 결과의 최종 Source다. 동시성 제어의 설계 근거는 [04_Concurrency_Control.md](./04_Concurrency_Control.md), 과거 문제와 회귀 테스트는 [08_Troubleshooting.md](./08_Troubleshooting.md)에서 다룬다.

---

## 1. Test Strategy

| 계층 | 목적 |
|---|---|
| Unit | Service/도메인 로직을 Mockito로 격리 검증 |
| Repository Slice (`@DataJpaTest`) | JPA 쿼리/매핑 정확성 검증 |
| Redis Slice (`@DataRedisTest`) | Redis Repository(락/큐/캐시/랭킹) 동작 검증 |
| MVC (`@WebMvcTest`) | Controller의 요청/응답 계약 검증 |
| Integration (`@SpringBootTest`) | 여러 계층을 통합한 실제 흐름 검증 |
| Concurrency | 실제 Testcontainers MySQL/Redis + 스레드 경합으로 동시성 제어 검증 |
| Load (k6) | 실제 HTTP 트래픽 기준 처리량/지연/정합성 검증 |

Mock 기반 단위 테스트는 실제 스레드 경합을 재현할 수 없기 때문에, 동시성 관련 검증은 별도로 Testcontainers 기반 통합 테스트(`ExecutorService`/`CountDownLatch`)와 k6 부하 테스트로 이원화되어 있다.

---

## 2. Test Inventory

- 테스트 클래스: **88개**
- `@Test` 메서드: **818개**

| 계층 | 대략적인 개수 |
|---|---|
| Repository Slice (`@DataJpaTest`) | 9 |
| Redis Slice (`@DataRedisTest`) | 10 |
| MVC (`@WebMvcTest`) | 8 |
| Integration (`@SpringBootTest`, Concurrency 테스트 포함) | 25 |
| 순수 Unit (Mockito, Spring Context 미사용) | 36 |

---

## 3. Testcontainers

`backend/src/test/java/com/gymflow/TestcontainersConfiguration.java`가 MySQL/Redis 컨테이너를 Spring Boot 3.1+ `@ServiceConnection`으로 등록한다. 별도의 `@DynamicPropertySource`로 접속 정보를 수동 연결할 필요가 없다.

```java
@Bean @ServiceConnection
MySQLContainer mysqlContainer() { return new MySQLContainer(DockerImageName.parse("mysql:latest")); }

@Bean @ServiceConnection(name = "redis")
GenericContainer<?> redisContainer() {
    return new GenericContainer<>(DockerImageName.parse("redis:latest")).withExposedPorts(6379);
}
```

모든 `@SpringBootTest`/`@DataJpaTest`/`@DataRedisTest` 클래스는 `@Import(TestcontainersConfiguration.class)`로 이 설정을 가져온다.

---

## 4. Concurrency Tests

| 테스트 클래스 | 검증 내용 |
|---|---|
| `ReservationConcurrencyTest` | 겹치지만 시작/종료 시각이 다른 동시 예약 요청 중 정확히 하나만 성공하는지 검증 |
| `AdminResourceConcurrencyTest` | 관리자 상태 변경과 진행 중인 예약/Promotion 사이의 경쟁이 write-skew 없이 처리되는지 검증 |
| `WaitingQueueConcurrencyTest` | 다수 사용자의 동시 대기열 등록 시 순번 배정이 Redis 순서와 실제 승급 순서에 일치하는지 검증 |
| `PromotionConcurrencyTest` | Promotion(ACCEPT/OFFERED 생성)과 일반 예약 생성이 `ReservationSlotLock`을 공유하며 경쟁할 때의 정합성 검증 |
| `LockTransactionCompletionIntegrationTest` | 분산 락 해제 시점이 실제 트랜잭션 완료(commit/rollback) 시점과 일치하는지 검증 |
| `WaitingQueueRedisRepositoryTest` | 대기열 ZSET 기반 순번 배정의 원자성(동시 등록 시 rank 중복 없음)을 20개 스레드로 검증 |
| `PromotionWebSocketCommitOrderingIntegrationTest` | WebSocket 알림 수신 시점에 DB에서 이미 promotionId를 조회할 수 있는지(커밋 이후 발행) End-to-End 검증 |

각 사례의 배경(과거 문제와 원인)은 [08_Troubleshooting.md](./08_Troubleshooting.md)에서 다룬다.

---

## 5. k6 — Same Resource / Same Time

동일 Resource, 동일 시간대에 50개 동시 요청을 발생시켜 정확히 하나만 성공해야 함을 검증한다(`k6/reservation-concurrency.js`).

| 항목 | 값 |
|---|---|
| 성공 | 1 |
| 충돌(409) | 49 |
| 평균 응답 | 235.97ms |
| p95 | 322.04ms |
| max | 345.10ms |
| HTTP 실패율 | 0% |

**PASS** — 50개 동시 요청 중 정확히 1건만 성공했고, 나머지는 모두 정상적으로 409 충돌로 처리되었다.

---

## 6. k6 — Non-contentious Baseline

서로 다른 Resource/시간대에 4개의 VU가 요청을 보내는 비경합 기준선 테스트다(`k6/reservation-normal-load.js`).

| 항목 | 값 |
|---|---|
| 성공(201) | 4 |
| 충돌 | 0 |
| 평균 응답 | 179.26ms |
| p95 | 186.90ms |

**PASS** — 경합이 없는 상황에서 모든 요청이 지연 없이 성공했다.

---

## 7. k6 — Ramping Reservation Load

`k6/reservation-sustained-load.js`는 k6의 `ramping-arrival-rate` executor로 점진적으로 부하를 올렸다 내리는 단시간 램핑 테스트다. 지속적인 고부하를 장시간 유지하는 Soak Test가 아니므로 이 문서에서는 **Ramping Load Test**로 표기한다.

| 항목 | 값 |
|---|---|
| iterations | 109 |
| 성공(201) | 109 |
| 충돌(409) | 0 |
| 평균 응답 | 64.19ms |
| p95 | 75.17ms |
| p99 | 101.19ms |
| max | 508.36ms |
| HTTP 실패율 | 0% |

**PASS** — 전체 109건이 충돌 없이 처리되었다. 전체 실행 시간(35.587s) 기준 iteration 처리율은 약 3.06/s(HTTP 요청 기준 약 4.33/s)이며, ramp-up/ramp-down 구간을 포함한 값이다.

---

## 8. k6 — WaitingQueue / Promotion

`k6/waiting-queue-promotion-load.js`는 20명이 동일 슬롯의 대기열에 등록한 뒤, 취소로 발생한 승급 기회에 대해 1명은 거절, 2번째 대기자가 수락하는 흐름을 검증한다.

| 항목 | 값 |
|---|---|
| 대기열 등록 요청 | 20 |
| 등록 성공 | 20 |
| 등록 충돌 | 0 |
| Promotion 생성 | 1 |
| 평균 응답(flow) | 145.31ms |
| p95 | 299.22ms |
| max | 349.12ms |
| HTTP 실패율 | 0% |

**PASS** — 20명 전원 대기열 등록에 성공했고, 취소 발생 시 정확히 1건의 Promotion이 생성되어 수락/거절 흐름이 설계대로 동작했다.

---

## 9. Target vs Measurement

[01_Project_Overview.md](./01_Project_Overview.md)에 명시된 성능 수치는 설계 초기에 설정한 **비기능 목표**이며, 아래 실제 측정 결과와는 별개다.

| 구분 | 예약 API | Resource 조회 API |
|---|---|---|
| 초기 목표 (평균) | 200ms 이하 | 100ms 이하 |
| 실제 측정 (평균, 비경합) | 179.26ms | — (별도 측정 없음) |
| 실제 측정 (p95, 비경합) | 186.90ms | — |

비경합 상황의 평균 응답 시간은 초기 목표를 만족했다. 다만 목표치는 평균만 정의했을 뿐 p95/p99 기준은 없었으므로, p95/p99는 목표와 직접 비교하지 않고 위 5~8절의 측정값 자체로 판단한다.

---

## 10. Prometheus/Grafana Cross Validation

k6 부하 테스트를 실행하는 동안 Grafana Dashboard의 `Reservation Requests`, `Reservation Conflict Rate`, `Distributed Lock Attempts`, `Waiting Queue Activity`, `Promotion Outcomes` 패널을 함께 관찰해, k6가 리포트한 성공/충돌 카운트가 애플리케이션이 자체 계측한 Micrometer 지표(`gymflow_lock_*`, Reservation/Promotion 관련 카운터)와 같은 방향으로 움직이는지 교차 확인했다. 두 경로(k6의 HTTP 레벨 결과, Prometheus의 애플리케이션 레벨 지표)가 서로 다른 관측 지점에서 동일한 결론(성공 1건 vs 충돌 49건 등)을 가리키는 것을 확인해, 특정 계측 지점의 오류로 인한 착시 가능성을 줄였다.

---

## 11. Performance Analysis

현재 측정 범위 안에서 다음을 확인했다.

- 4개 시나리오 모두 unexpected error(계측되지 않은 응답 코드)가 발생하지 않았다.
- 경합 상황(동일 Resource/동일 시간)에서도 정확히 하나의 요청만 성공해 correctness가 유지되었다.
- 비경합 요청은 지연 없이 정상 처리되었다.
- 목표 시나리오(대기열/승급 포함)에서 안정적으로 처리되었으며 서버 오류가 발생하지 않았다.

---

## 12. Optimization Decision

현재 목표 부하 범위에서 명확한 병목이 관측되지 않았기 때문에, 추가적인 성능 최적화(예: Lock 세분화, Connection Pool 튜닝, 쿼리 최적화)를 별도로 수행하지 않았다. 이는 "최적화를 하지 못한 것"이 아니라, 측정 결과에 기반해 현재 범위에서는 최적화 우선순위가 낮다고 판단한 **measurement-driven decision**이다. 더 높은 동시 사용자 규모를 목표로 할 경우 우선적으로 검토할 지점은 [04_Concurrency_Control.md](./04_Concurrency_Control.md#10-trade-off)의 `ResourceAvailabilityLock` coarse-grained 트레이드오프다.

---

## 13. Load Test Reliability

k6 스크립트 자체도 결과의 신뢰성을 위해 설계에 안전장치를 두었다.

- `reservation-normal-load.js`(비경합 기준선)와 `reservation-sustained-load.js`(램핑 부하)는 VU/iteration마다 서로 다른 Resource 또는 겹치지 않는 시간 슬롯을 할당해, 테스트 스크립트 자체의 요청 충돌로 결과가 오염되지 않도록 설계되어 있다.
- `reservation-concurrency.js`(경합 시나리오)는 반대로 모든 VU가 동일한 `resourceId`+`startAt`을 목표로 동시에 요청을 발사(`fireAt` 동기화)하도록 설계해, 의도한 경합 조건을 정확히 재현한다.

---

## Next Document

[08_Troubleshooting.md](./08_Troubleshooting.md)

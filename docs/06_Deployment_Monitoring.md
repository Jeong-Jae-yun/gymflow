# GymFlow Deployment & Monitoring

## Document Information

| 항목 | 내용 |
|------|------|
| Document | Deployment & Monitoring |
| Version | v1.0 |
| Author | 정재윤 |
| Last Updated | 2026-09-04 |

이 문서는 GymFlow를 어떻게 배포하고 운영 상태를 관찰하는지를 다룬다. 전체 시스템 구조는 [03_Architecture.md](./03_Architecture.md)를 참고한다.

---

## 1. Deployment Architecture

| 구성 요소 | 기술 |
|---|---|
| Frontend | Vercel |
| External HTTPS/WSS ingress | Cloudflare Quick Tunnel |
| Reverse Proxy | Nginx (EC2) |
| Backend | Spring Boot, EC2 Docker container |
| Database | AWS RDS MySQL |
| Redis | EC2 Docker Redis |
| Image Storage | AWS S3 |
| Monitoring | Prometheus + Grafana (EC2 Docker) |

Frontend(Vercel)와 Cloudflare Quick Tunnel, EC2 인스턴스 구성은 저장소 코드만으로 완전히 검증할 수 없는 운영 설정이지만, 이 프로젝트에서 실제로 사용한 배포 구조로 확정된 정보다.

**Cloudflare Quick Tunnel에 대한 명확화**: Quick Tunnel은 별도 도메인 등록 없이 임시 공개 URL을 발급받아 HTTPS/WSS ingress로 사용할 수 있는 Cloudflare의 기능이다. 이 프로젝트에서는 포트폴리오 통합 환경을 위한 임시 ingress로 사용했으며, 운영 등급의 안정적인 production ingress는 아니다. 프로세스 재시작 시 URL이 바뀔 수 있고, Cloudflare 측의 세션 유지에 의존한다. 실제 production 환경이라면 고정 도메인 + Cloudflare Tunnel(named tunnel) 또는 ALB 등으로 대체해야 한다.

---

## 2. Docker

`backend/Dockerfile`은 multi-stage 빌드로 구성된다.

```
FROM eclipse-temurin:21-jdk AS builder
...
RUN ./gradlew bootJar -x test --no-daemon

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
RUN useradd --uid 1001 appuser
COPY --from=builder /app/app.jar app.jar
USER appuser
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

- Build stage: JDK 21(Eclipse Temurin)에서 테스트를 제외하고 `bootJar`만 빌드한다(테스트는 CI에서 별도로 실행).
- Runtime stage: JRE 21 슬림 이미지에 jar만 복사해, 빌드 도구가 포함되지 않은 실행 전용 이미지를 만든다.
- 전용 non-root 사용자(`appuser`, uid 1001)로 실행한다.

Docker Compose 파일은 목적별로 분리되어 있다.

| 파일 | 위치 | 용도 |
|---|---|---|
| `compose.yaml` | repo root | 로컬 개발용. backend를 직접 빌드하고, MySQL 8.0/Redis 7을 로컬 포트로 노출 |
| `compose.prod.yaml` | repo root | 운영용. Nginx(80) → backend(내부 expose만) → Redis, 모두 `gymflow-prod-network`에 격리. backend는 GHCR 이미지를 pull해서 실행 |
| `compose.monitoring.yaml` | repo root | 운영 모니터링. Prometheus + Grafana(127.0.0.1:3000 바인딩), `compose.prod.yaml`이 만든 외부 네트워크에 join |
| `backend/docker-compose.monitoring.yml` | backend/ | 개발 단계에서 사용한 이전 버전의 모니터링 스택(Prometheus 9090/Grafana 3000을 공개 포트로 노출, `host.docker.internal` 사용). 운영에서는 root의 `compose.monitoring.yaml`로 대체됨 |

---

## 3. CI

`.github/workflows/ci.yml` — PR/`main` push 시 실행.

1. Checkout, JDK 21(Temurin) 설정, `gradle/actions/setup-gradle`
2. `./gradlew compileJava`
3. `./gradlew compileTestJava`
4. `./gradlew test --rerun`
5. 실패 시 테스트 리포트를 아티팩트로 업로드

---

## 4. CD

`.github/workflows/cd.yml` — CI가 `main`에서 성공적으로 완료된 뒤(`workflow_run`) 실행.

```
CI 성공 (workflow_run)
  → CI가 검증한 정확한 commit SHA checkout
  → GHCR 로그인 (GITHUB_TOKEN, 별도 PAT 없음)
  → docker buildx로 이미지 빌드/푸시 (tag: SHA + latest)
  → AWS OIDC로 IAM Role Assume (장기 Access Key 없음)
  → AWS SSM send-command(AWS-RunShellScript)로 EC2에 배포 스크립트 전달
      - git fetch/checkout <SHA>
      - docker compose --env-file .env.prod -f compose.prod.yaml pull backend
      - docker compose ... up -d --no-deps backend
      - curl localhost/actuator/health 재시도 (최대 10회, 5초 간격) — 스모크 테스트
  → GitHub Actions가 SSM 명령 완료 상태를 최대 5분간 polling, 실패 시 Job 실패
```

이미지 저장소로 GHCR을 사용하며(ECR 아님), 배포 대상 지정과 원격 명령 실행은 SSM RunCommand로 수행한다(SSH 직접 접속 없음).

---

## 5. OIDC

GitHub Actions는 장기 AWS Access Key를 Secrets에 저장하지 않는다. 대신 `aws-actions/configure-aws-credentials`가 GitHub의 OIDC(OpenID Connect) 토큰으로 AWS IAM Role을 Assume해 배포 작업에 필요한 만큼만 유효한 단기 자격 증명을 발급받는다. 워크플로 권한에 `id-token: write`를 명시해야 하며, `role-to-assume` + `aws-region`만 설정하고 별도 Access Key/Secret Key는 어디에도 저장하지 않는다. 이 방식은 자격 증명이 워크플로 실행이 끝나면 자동 만료되므로, Secrets 유출 시의 피해 범위를 줄인다.

---

## 6. Nginx

`nginx/nginx.conf`

- `location /ws`: `http://backend:8080`으로 프록시하며 `proxy_http_version 1.1`, `Upgrade`/`Connection` 헤더(맵을 통한 조건부 upgrade)로 WebSocket을 지원한다. `proxy_read_timeout 60m; proxy_send_timeout 60m;`로 장시간 유지되는 WebSocket 연결을 허용한다.
- `location /`: 동일한 backend로 일반 HTTP 요청을 프록시하며 `Host`/`X-Real-IP`/`X-Forwarded-For`/`X-Forwarded-Proto` 헤더를 전달한다.

---

## 7. Monitoring Pipeline

```
Spring Boot (Micrometer)
  → /actuator/prometheus
  → Prometheus (scrape_interval: 15s)
  → Grafana (Dashboard: "GymFlow Monitoring")
```

Prometheus 설정은 개발/운영 두 버전이 있으며 scrape 대상 호스트명만 다르다(`host.docker.internal:8080` vs `backend:8080`).

---

## 8. Metrics

| 구분 | 지표 |
|---|---|
| System | JVM (Heap 등), HTTP 요청/응답 시간, HikariCP Connection Pool, Redis Command Latency |
| Business | Reservation 요청/충돌률, 분산 Lock 획득/실패, WaitingQueue 활동/현재 크기, Promotion 결과, WebSocket 연결/알림 |

---

## 9. Grafana Dashboard

`backend/grafana/dashboards/gymflow-monitoring.json`, 총 14개 패널로 구성된다(Provisioning: `backend/grafana/provisioning/`).

| # | Panel |
|---|---|
| 1 | JVM Heap Memory Used |
| 2 | HTTP Request Rate |
| 3 | HTTP Response Time |
| 4 | DB Connection Pool |
| 5 | Reservation Requests |
| 6 | Reservation Conflict Rate |
| 7 | Distributed Lock Attempts |
| 8 | Lock Acquisition Attempt Latency (reservation-slot) |
| 9 | Waiting Queue Activity |
| 10 | Current Waiting Queue Size |
| 11 | Promotion Outcomes |
| 12 | WebSocket Active Connections |
| 13 | WebSocket Notifications |
| 14 | Redis Command Latency |

8번 패널명이 "Lock Acquisition Attempt Latency"인 이유는 [04_Concurrency_Control.md](./04_Concurrency_Control.md#9-metrics)에서 설명한 것처럼, 이 지표가 락 대기 시간이 아니라 재시도 없는 단발성 획득 시도의 latency이기 때문이다.

---

## 10. Operational Characteristics

과장 없이, 현재 운영 구조가 가진 특성을 그대로 기록한다.

- `/actuator/prometheus`는 `SecurityConfig`에서 `permitAll`로 열려 있으며, 별도의 외부 접근 차단(방화벽/Nginx 레벨 차단 등)은 적용하지 않았다. 현재는 Prometheus가 컨테이너 내부 네트워크에서 스크랩하지만, 외부에서 이 엔드포인트에 직접 접근하는 것을 막는 hardening은 적용되어 있지 않다.
- Cloudflare Quick Tunnel URL은 터널 프로세스가 재시작되면 바뀔 수 있다. 고정 도메인이 필요하면 named tunnel 등으로 전환해야 한다.
- Redis keyspace notification(TTL 만료 이벤트 감지)은 별도의 `notify-keyspace-events` 설정 없이 Spring Data Redis가 기동 시점에 자동으로 구성한다([05_Redis_Realtime.md](./05_Redis_Realtime.md#4-ttl-lifecycle) 참고). `CONFIG SET`이 제한된 관리형 Redis로 이전할 경우 별도 설정이 필요하다.

이 항목들은 현재 프로젝트의 실패가 아니라, 실제 production 환경으로 확장할 때 우선적으로 다뤄야 할 향후 개선 과제로 남겨둔다.

---

## Next Document

[07_Test_Performance.md](./07_Test_Performance.md)

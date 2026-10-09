# 개발 환경 이해 — Docker · Flyway · Neon · 의존성 · 병합

이 프로젝트의 도구들이 **무엇이고, 서로 어떻게 연결되고, 눈으로 어떻게 확인하는지**를 정리한 문서다. 규칙(무엇을 하면 안 되는지)은 [CLAUDE.md](../CLAUDE.md)가 기준이고, 이 문서는 그 규칙의 배경을 설명한다.

기준일: 2026-09-29 (V1 Neon 적용, 로컬 Docker 설치 완료 시점). 2026-10-07 로컬 Docker DB 프로필(`local-docker`) 추가

---

## 1. 전체 그림

```
[테스트할 때]  ./gradlew build  (또는 test)
   테스트 코드 ──▶ Docker 안 임시 PostgreSQL   매번 새로 만들고 버림 · 인터넷 불필요

[앱을 실행할 때 — 기본]  docker compose up -d → ./gradlew bootRun
   Spring Boot 앱 ──▶ 내 PC의 Docker PostgreSQL (localhost:5433)   연습 데이터 · 지우고 다시 시작 가능

[Neon에 붙을 때만]  $env:SPRING_PROFILES_ACTIVE='local'; ./gradlew bootRun
   Spring Boot 앱 ──인터넷(SSL)──▶ Neon PostgreSQL   진짜 데이터 · 계속 남음
     ├ 앱(JPA):  -pooler 주소 (연결을 여럿이 나눠 쓰는 창구)
     └ Flyway:   직통 주소    (마이그레이션 전용 창구)
```

- **Docker와 Neon은 서로 연결되지 않는다.** Docker 속 DB는 연습장이고, Neon은 실제 장부다
- 둘을 이어 주는 것은 **같은 마이그레이션 파일**(`db/migration/V*.sql`)뿐이다. 두 DB 모두 같은 파일을 같은 순서로 적용하므로, 연습장에서 통과한 테스트가 실제 장부에서도 성립한다고 믿을 수 있다
- 인터넷이 끊겨도 테스트와 기본 `bootRun`은 Docker로 돈다. Neon은 일부러 `local` 프로필을 지정할 때만 쓴다

### 왜 기본이 Docker인가 (2026-10-07)
`ledger_entries`는 append-only라 Neon에 들어간 연습 충전·사용은 **지울 수 없다**. 손으로 API를 시험할 일이 많아지는 S3부터는 실수로 실제 장부에 쓰지 않도록 `bootRun`의 기본 프로필을 로컬 Docker DB(`local-docker`)로 두었다. 테스트 계획서 §6의 E2E 환경('로컬 도커 스택')도 이 DB를 쓴다.

| 프로필 | 설정 파일 (`backend/`) | 붙는 DB | 켜는 법 |
|---|---|---|---|
| `local-docker` (기본) | `application-local-docker.yml` (커밋됨, 비밀 값 없음) | `localhost:5433` Docker | 아무것도 지정하지 않음 |
| `local` | `application-local.yml` (gitignore, 양식은 `.example`) | Neon | `$env:SPRING_PROFILES_ACTIVE='local'` — 그 터미널 창에서만 유지 |

- Docker DB를 켜지 않고 기본 `bootRun`을 하면 접속 실패로 기동이 멈춘다. Neon으로 넘어가지 않는다
- 로컬 Docker DB는 비밀번호 없이(trust) 접속한다. 대신 포트를 `127.0.0.1`에만 열어 이 PC 밖에서는 붙을 수 없다
- `/actuator/health`의 세부 정보(DB 종류, 디스크 용량)는 두 로컬 프로필에서만 보인다. 배포 기본값(`application.yml`)은 `never`라 `{"status":"UP"}`만 나온다 — 내부 정보 노출 방지

## 2. Docker

**프로그램을 담는 도시락통.** 프로그램과 그 실행 환경을 한 통에 담아 두고, 필요할 때 열어 쓰고 통째로 버린다.

| 용어 | 뜻 |
|---|---|
| 이미지 | 도시락 레시피. 예: `postgres:18-alpine` (인터넷에서 한 번 받아 두면 재사용) |
| 컨테이너 | 레시피로 만든 도시락 한 개 = 실제로 돌아가는 PostgreSQL |
| Docker 엔진 | 도시락을 만들고 치우는 주방 |
| WSL 2 | Windows 안의 작은 리눅스 방. 컨테이너가 리눅스용이라 그 안에 주방을 차린다 |

### 이 프로젝트에서 하는 일 — Testcontainers
통합 테스트가 시작되면 Testcontainers가 ① Docker에 PostgreSQL 컨테이너를 띄우고 ② 거기에 마이그레이션을 적용한 뒤 ③ 테스트를 돌리고 ④ 컨테이너를 버린다. H2 같은 흉내 DB를 쓰지 않는 이유는 생성 컬럼·부분 인덱스·트리거가 운영 PostgreSQL과 똑같이 동작해야 하기 때문이다.

### 설치 상태 (2026-09-29)
- Docker Desktop 29.8.1, WSL 2 백엔드. 관리자 권한 없이 설치해서 위치는 `%LOCALAPPDATA%\Programs\DockerDesktop`
- Windows 11 Home에는 Hyper-V가 없으므로 설치 화면에 "Use WSL 2 instead of Hyper-V" 체크란이 **안 나오는 게 정상**이다
- 로컬 `./gradlew build`에서 Testcontainers 테스트 21건 실행·통과(스킵 0)

### 눈으로 확인하기 (Docker Desktop)
| 메뉴 | 보이는 것 |
|---|---|
| Images | `postgres`, `testcontainers/ryuk`. 날짜("1 year ago" 등)는 **이미지를 만든 날**이지 받은 날이 아니다 |
| Containers | `docker compose up -d` 뒤에는 `creditbook-db`가 계속 떠 있다. 그 밖에는 `./gradlew test`를 돌리는 동안 `postgres`와 `ryuk`이 잠깐 나타났다 사라진다 |

- `testcontainers/ryuk`: 청소부 컨테이너. 테스트가 끝나거나 비정상 종료돼도 남은 컨테이너를 지운다
- **Resource Saver mode**: 컨테이너가 없을 때 엔진을 잠시 멈춰 두는 절전 기능. `docker` 명령이나 테스트가 오면 알아서 깨어난다. 테스트가 기동 대기로 시간 초과 나면 Settings → Resources에서 끈다
- 테스트할 때와 기본 `bootRun`(로컬 Docker DB)을 쓸 때 Docker Desktop이 켜져 있어야 한다

### 로컬 개발 DB — `docker-compose.yml` (리포 루트)
| 하고 싶은 것 | 명령 (리포 루트에서) |
|---|---|
| 켜기 | `docker compose up -d` (첫 `bootRun` 때 Flyway가 V1부터 적용) |
| 잠시 끄기 (컨테이너·데이터 유지) | `docker compose stop` — 화면의 ■ Stop과 같다 |
| 끄고 컨테이너 지우기 (데이터 유지) | `docker compose down` — 화면의 🗑 Delete와 같다 |
| 연습 데이터 전부 지우고 처음부터 | `docker compose down -v` |
| 안에서 SELECT 해 보기 | `docker exec -it creditbook-db psql -U creditbook -d creditbook` |

DB는 두 부분으로 나뉜다. **컨테이너**(`creditbook-db`)는 DB 프로그램이 돌아가는 상자이고, **볼륨**(`creditbook_creditbook-db-data`)은 실제 데이터가 저장된 곳이다. `stop`은 상자를 멈추기만 하고, `down`은 상자를 지우되 볼륨은 남긴다. 둘 다 연습 데이터는 남고, 다시 켤 때는 `docker compose up -d`(상자가 없으면 새로 만들어 남은 볼륨을 다시 연결)를 쓴다. 볼륨까지 지우는 것은 `-v`뿐이다.

이미지는 Testcontainers와 같은 `postgres:18-alpine`이다. 연습 DB이므로 `ledger_entries`가 쌓여도 `down -v`로 통째로 버리면 된다(트리거가 막는 UPDATE·DELETE로 지우지 않는다).

#### 화면으로 하기 (Docker Desktop)
명령어 대신 Docker Desktop 화면에서도 같은 일을 할 수 있다. 목록에는 폴더 이름을 딴 묶음 `creditbook`이 보이고, 펼치면 컨테이너 `creditbook-db`가 있다.

| 하고 싶은 것 | 화면에서 | 명령과의 차이 |
|---|---|---|
| 켜져 있는지 보기 | **Containers** → `creditbook` 줄의 상태가 초록 점(Running) | — |
| 잠시 끄기 (컨테이너·데이터 유지) | 같은 줄의 ■ **Stop** | `docker compose stop`과 같다. 컨테이너는 남겨 두고 멈추기만 한다(`down`은 컨테이너를 지운다). 데이터는 둘 다 남는다 |
| 다시 켜기 | 같은 줄의 ▶ **Start** | Stop으로 멈춘 경우에만 된다. `down`·Delete로 컨테이너를 지웠다면 터미널에서 `docker compose up -d` |
| 끄고 컨테이너 지우기 (데이터 유지) | 같은 줄의 🗑 **Delete** | `docker compose down`과 같다 |
| 연습 데이터까지 지우기 | 컨테이너를 Delete한 뒤 **Volumes** → `creditbook_creditbook-db-data` → 🗑 Delete | `docker compose down -v`와 같다 |
| 로그 보기 | `creditbook-db` 클릭 → **Logs** 탭 | DB가 안 뜰 때 원인 확인용 |

- 처음 만들 때(`up -d`)는 터미널이 필요하다. 화면에는 `docker-compose.yml`을 읽어 새로 만드는 버튼이 없다
- 컨테이너가 없거나 멈춘 상태에서 기본 `bootRun`을 하면 접속 실패로 기동이 멈춘다 — Neon으로 넘어가지 않는다

#### 개발용 직원 넣기 (로그인 시험용)
로그인하려면 `employees`에 직원이 있어야 한다. 로컬 DB에 V3까지 적용된 뒤(기본 `bootRun` 한 번) 리포 루트에서 실행한다. 여러 번 실행해도 같은 아이디는 건너뛴다. (파일을 컨테이너에 복사해 실행한다 — PowerShell 파이프(`Get-Content | docker exec`)는 한글 이름을 깨뜨릴 수 있다)

```powershell
docker cp backend/dev/seed-local.sql creditbook-db:/tmp/seed-local.sql
docker exec creditbook-db psql -U creditbook -d creditbook -v ON_ERROR_STOP=1 -v owner_pw=<사장 비밀번호> -v staff_pw=<직원 비밀번호> -f /tmp/seed-local.sql
```

아이디는 ADMIN `owner`, STAFF `staff`이고, 비밀번호는 실행할 때 `<…>` 자리에 직접 정한다. 리포가 공개 저장소라 비밀번호와 해시를 파일에 두지 않는다 — DB가 pgcrypto로 그 자리에서 BCrypt 해시를 만든다. **로컬 Docker DB 전용이다 — Neon에는 실행하지 않는다.**

### 로그인 서명 키 — `CREDITBOOK_JWT_SECRET`
로그인 토큰(JWT)의 서명 키. 앱은 이 환경변수에서만 읽고, 없거나 32바이트 미만이면 기동하지 않는다. 키 값은 파일에 쓰지 않는다(DB 비밀번호와 같은 규칙). 아래 한 줄이 48바이트 난수를 만들어 **화면에 출력하지 않고** Windows 사용자 환경변수에 바로 저장한다.

```powershell
$b = New-Object byte[] 48; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); [Environment]::SetEnvironmentVariable('CREDITBOOK_JWT_SECRET', [Convert]::ToBase64String($b), 'User')
```

- 저장한 뒤 터미널·IDE를 새로 열어야 값이 보인다
- 키를 바꾸면 이미 로그인한 세션이 모두 끊긴다(다시 로그인하면 된다)
- 테스트·CI에는 필요 없다. 테스트는 실행할 때마다 무작위 키를 만들어 쓴다

## 3. Flyway

**DB 구조 변경(마이그레이션)을 순서대로 적용하고 기록하는 Java 라이브러리.** 따로 설치하는 프로그램이 아니라 `build.gradle`의 의존성으로 앱 안에 들어 있고, 앱이 시작될 때 DB를 쓰기 전에 먼저 실행된다.

### `bootRun` 때 일어나는 일 (`local` 프로필 = Neon 기준. `local-docker`는 같은 흐름에서 대상만 `localhost:5433`이다)
```
[내 PC]  $env:SPRING_PROFILES_ACTIVE='local'; ./gradlew bootRun
   Spring Boot 앱 시작
     └ Flyway (앱 안의 라이브러리)
         ① classpath:db/migration (= src/main/resources/db/migration) 에서 V1, V2 … 목록을 읽음
         ② Neon에 접속해 flyway_schema_history 조회 ──▶ [Neon] "v1까지 적용됨"
         ③ 비교해서 기록에 없는 파일만 순서대로 SQL 전송 ──▶ [Neon] 실행 + 기록 추가
     └ 그다음 앱 정상 기동 (Tomcat 8080)
```
- 방향은 항상 **내 PC(Flyway) → Neon** 한쪽이다. Neon은 폴더를 보지 않고 받은 SQL을 실행할 뿐이다
- 파일 위치는 `application.yml`의 `spring.flyway.locations`, 접속 주소는 `application-local.yml`의 `spring.flyway.url`(직통 주소)

### Flyway가 지켜 주는 것
| 할 일 | 방법 |
|---|---|
| 적용 순서 | 파일 이름의 번호 `V1__`, `V2__` … |
| 어디까지 했는지 | 대상 DB의 `flyway_schema_history`에 파일·체크섬 기록 |
| 중복 적용 방지 | 기록에 있는 파일은 건너뜀 |
| 몰래 바뀐 파일 감지 | 적용된 파일의 체크섬이 기록과 다르면 **앱 기동을 멈춤** → 적용된 V1은 절대 고치지 않는다 |
| 동시 실행 방지 | 적용 중 DB 잠금(세션 advisory lock) → 그래서 Flyway만 `-pooler` 없는 직통 주소를 쓴다 |

### 규칙으로 이어지는 점
- V1은 2026-09-29 Neon에 적용됐다. 스키마를 바꾸려면 `V2__설명.sql`을 **새로** 만든다
- 새 파일을 만든 뒤 `local` 프로필의 첫 `bootRun`은 Neon에 **되돌릴 수 없게** 적용된다. Testcontainers 테스트 통과 + 사용자 확인 후에 실행한다. 기본(`local-docker`) `bootRun`은 로컬 Docker DB에만 적용되므로 먼저 거기서 확인해 볼 수 있다

## 4. Neon

클라우드의 서버리스 PostgreSQL(18.6). 실제 데이터가 쌓이는 곳이다. 앱은 테이블 소유자 계정 `neondb_owner`로 접속하고, 비밀번호는 Windows 사용자 환경변수 `CREDITBOOK_DB_PASSWORD`에서만 읽는다.

### 눈으로 확인하기 (https://console.neon.tech → 프로젝트 선택)
| 메뉴 | 확인할 것 |
|---|---|
| Tables | `neondb` → `public`: 테이블 6개(`store_profile`, `employees`, `customers`, `prepaid_accounts`, `ledger_entries`, `phone_access_logs`) + `flyway_schema_history` |
| SQL Editor | 아래 SELECT를 붙여넣고 Run |

```sql
-- ① 마이그레이션 기록 (success = true)
SELECT version, description, success, installed_on FROM flyway_schema_history;

-- ② 테이블 목록
SELECT table_name FROM information_schema.tables
WHERE table_schema = 'public' AND table_type = 'BASE TABLE' ORDER BY 1;

-- ③ append-only 트리거 2개
SELECT tgname FROM pg_trigger WHERE tgrelid = 'ledger_entries'::regclass AND NOT tgisinternal;
```

> SQL Editor에서는 **SELECT만** 쓴다. `ledger_entries` UPDATE/DELETE 실험은 트리거가 막아 주더라도 운영 장부에서 하지 않는다 — 그런 실험은 Docker 테스트(`LedgerEntriesAppendOnlyTest`)가 대신한다.

- 함수 목록에 보이는 `crypt`, `gen_random_uuid` 등은 V1 첫 줄 `CREATE EXTENSION pgcrypto`가 설치한 것이다

## 5. `build.gradle` 의존성 읽는 법

### 줄 앞 키워드
| 키워드 | 뜻 |
|---|---|
| `implementation` | 앱 코드가 직접 쓰는 부품. 실행 시에도 포함 |
| `runtimeOnly` | 코드에서 직접 부르진 않지만 실행할 때 필요 |
| `testImplementation` | 테스트 코드에서만. 배포되는 앱에는 안 들어감 |
| `testRuntimeOnly` | 테스트 실행에만 필요 |

버전 번호가 없는 것이 정상이다. plugins의 Spring Boot 4.0.8이 호환 버전 목록(BOM)을 갖고 있고, `io.spring.dependency-management`가 그 버전을 맞춰 준다.

### 앱에 들어가는 부품
| 부품 | 하는 일 |
|---|---|
| `starter-webmvc` | 웹 서버(Tomcat, 8080) + REST API. 컨트롤러가 이 위에서 돈다 |
| `starter-data-jpa` | JPA/Hibernate: Java 객체 ↔ 테이블. `@Version` Optimistic Lock(낙관적 락)도 여기서 나온다 |
| `starter-flyway` + `flyway-database-postgresql` | 마이그레이션 적용. 뒤의 것은 PostgreSQL 지원 모듈 |
| `starter-validation` | 요청 DTO 입력 형식 검사(`@NotNull`, `@Positive` 등) |
| `starter-actuator` | 상태 점검 창구 `/actuator/health`. 배포 후 스모크에 쓴다 |
| `starter-security-oauth2-resource-server` | Spring Security + JWT 발급·검증(Nimbus). 로그인 쿠키의 토큰으로 요청마다 직원을 확인한다 |
| `postgresql` (runtimeOnly) | JDBC 드라이버. JPA와 Flyway 모두 이것으로 DB에 접속한다 |

**Flyway는 테이블을 만들고, JPA는 만들어진 테이블에 데이터를 읽고 쓴다.** `ddl-auto: validate`는 "JPA는 테이블을 만들거나 바꾸지 말고, 엔티티와 테이블이 맞는지 확인만 하라"는 뜻이다.

### 테스트 전용 부품
| 부품 | 하는 일 |
|---|---|
| `*-test` 7개 | 기능별 테스트 도구. JUnit5, AssertJ(`assertThat`), Mockito가 딸려 온다 (Boot 4부터 기능별로 쪼개짐) |
| `spring-boot-testcontainers`, `testcontainers-*` | Docker로 임시 PostgreSQL을 띄운다 |
| `postgresql` (testImplementation) | 제약 위반 시 어떤 제약이 막았는지 `PSQLException`에서 이름을 꺼내 검증하려고 직접 쓴다 |
| `junit-platform-launcher` | Gradle이 JUnit5를 실행하게 해 주는 연결 부품 |

### 플러그인
- `jacoco`: 테스트 커버리지. 테스트 후 `backend/build/reports/jacoco/test/html/index.html`을 브라우저로 연다
- `toolchain 17`: PC에 다른 Java가 있어도 17로 빌드

### 앞으로 추가될 것
- PIT 뮤테이션 테스트 (`domain` 패키지 테스트가 버그를 실제로 잡는지 검증)

## 6. PR 병합과 정리

- GitHub에서 **Create a merge commit** → Confirm merge로 병합한다. 병합 방식은 merge commit만 쓴다 (squash·rebase 금지)
- CI(`backend build & test`)가 통과하지 않으면 `main` 보호 규칙 때문에 병합 버튼이 막힌다
- 쌓인 PR이 있으면 원격 브랜치를 지우기 전에 다음 PR의 base를 `main`으로 먼저 바꾼다 (CLAUDE.md 병합 절차)

병합 후 로컬 정리:
```powershell
git switch main
git pull --ff-only
git fetch --prune
git branch -d <병합된 브랜치>
git push origin --delete <병합된 브랜치>   # GitHub에 남아 있을 때
```

## 7. 자주 쓰는 명령

| 하고 싶은 것 | 명령 (`backend/`에서) | 전제 |
|---|---|---|
| 전체 빌드 + 테스트 | `./gradlew build` | Docker Desktop 켜짐 |
| 테스트만 | `./gradlew test` | Docker Desktop 켜짐 |
| 앱 실행 (로컬 Docker DB, 기본) | `./gradlew bootRun` | 리포 루트에서 `docker compose up -d` |
| 앱 실행 (Neon 접속) | `$env:SPRING_PROFILES_ACTIVE='local'; ./gradlew bootRun` | 새 마이그레이션이 있으면 **먼저 사용자 확인** |
| 앱 상태 | 브라우저에서 `http://localhost:8080/actuator/health` | 앱 실행 중. 로컬에서는 `components.db`까지 보인다 |
| Docker 동작 확인 | `docker run --rm hello-world` | Docker Desktop 켜짐 |

> VS Code가 Docker 설치 전에 켜져 있었다면 터미널이 `docker` 명령을 못 찾는다. VS Code를 완전히 껐다 다시 연다.

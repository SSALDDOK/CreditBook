# CreditBook — 클로드 코드 작업 지침

이 문서는 Notion 워크스페이스(단일 출처)의 스냅샷이다. 스키마·컨벤션이 바뀌면 Notion이 먼저 바뀌고 이 파일은 그에 맞춰 갱신된다. 핵심 규칙은 Notion에 연결되지 않은 상태에서도 지킬 수 있도록 이 파일에 그대로 둔다.

- **Notion 메인**: https://app.notion.com/p/3e2966a227db8182a452fa361b4f20a1 — 스키마 정의서·개발 컨벤션·운영 점검 가이드·테스트 계획서·요구사항/화면/테스트 케이스/버그 DB 등 모든 문서의 입구. 이 파일에서 "스키마 정의서 §6"처럼 문서를 가리키면 메인에서 찾아 들어간다
- **세션 시작 점검용 DB** (메인을 거치지 않고 바로 조회):
  - 인수인계 요청: `collection://ceb255ee-edcd-49b5-a8bc-c63022a6acdf`
  - 스프린트 보드: `collection://cb292543-5bea-46aa-8970-9191533e66df`
- 워크스페이스 인덱스(결정 로그 등): 리포와 별개로 Claude 계정의 Project 문서에 있다 — 필요하면 사용자에게 요청

마지막 동기화: 2026-09-27 (S1 종료·이월 반영, 세션 시작 점검에 스프린트 보드 추가)

## 세션 시작 점검 (Notion 읽기 전용)
1. **인수인계 요청 DB**: `담당 = Claude Code`, `상태 = 대기`인 행을 확인해 사용자에게 알린다. 처리는 사용자가 승인한 뒤에 한다 (알림만, 자동 처리 없음)
2. **스프린트 보드**: 오늘 날짜가 속한 스프린트와 상태를 확인한다. 아래 로드맵의 "현재" 표시와 다르거나, 스프린트 종료가 2일 이내이거나, 이월 항목이 있으면 알린다. 로드맵 표시는 이 파일이므로 Claude Code가 고치되, 사용자 승인 후에 고친다
3. Notion 연결이 없으면 점검을 건너뛰었다고 말하고 이 파일 기준으로 작업한다
4. 스프린트 상태·이월 기록 등 Notion 쪽 수정이 필요하면 직접 고치지 않고 인수인계 요청 DB에 요청을 쓴다

## 코워크와의 인수인계 (인수인계 요청 DB)
Notion·Jira는 코워크가, 리포(CLAUDE.md·코드·`.claude/`)는 Claude Code가 담당한다. 서로에게 넘길 일은 파일이 아니라 **인수인계 요청 DB**로 주고받는다 (Notion 메인의 "인수인계 요청" 섹션)
- **요청 작성 시**: 결정이 필요하거나 Notion·Jira 반영이 필요한 게 생기면 이 DB에 행을 추가한다 (`요청자 = Claude Code`, `담당 = 코워크`). 사용자 결정이 먼저 필요하면 `상태 = 사용자 확인 필요`, 아니면 `대기`
- **긴급 항목**: `긴급도 = 긴급`이면 DB에 쓰는 것과 별개로 **그 자리에서 사용자에게 바로 말한다** — 코워크는 인수인계 DB를 매일 1회 점검하므로 최대 하루 늦을 수 있다
- **완료 처리 시**: `처리 결과`에 바뀐 파일·페이지 또는 Jira 키를 남긴다
- Notion·Jira를 Claude Code가 직접 고치지 않는다. 반대로 코워크는 리포를 건드리지 않는다

### 기술 설계는 리포가 먼저 (2026-09-28 사용자 결정 — "Notion이 먼저" 원칙의 예외)
스키마·기술 설계(인덱스, 트리거, 권한, 제약조건, JPA 매핑 등)는 Claude Code가 설계하고, 코워크는 문서로 정리한다.
1. Claude Code가 설계안을 만들고 **Neon에서 롤백 전용 트랜잭션으로 검증**한다 (commit 금지, 끝나면 잔여 객체 없음 확인)
2. 설계안을 채팅으로 제시하고 **사용자가 채팅에서 승인**한다
3. 승인 즉시 Claude Code가 리포(마이그레이션·코드·이 파일)에 구현한다. 그리고 인수인계 요청 DB에 **Notion 문서의 절 구조(§ 번호·표·콜아웃)에 맞춘 문구 그대로**(SQL, 근거, 검증 결과, 변경 이력 행)를 적어 요청한다
4. 코워크가 스키마 정의서·개발 컨벤션 등에 옮겨 적는다. 그때까지 리포가 Notion보다 앞서 있는 것은 허용되며, 불일치는 이 요청 행으로 추적한다
- 업무 규칙 자체(요구사항, 취소 조건, 권한 정책 등)를 바꾸는 판단은 이 예외에 해당하지 않는다. 계속 Notion(요구사항 DB·결정 로그)이 먼저다

## 스프린트 로드맵 (현재: S2 — 09.28 시작, 이월된 백엔드 셋업 최우선)
1인 6주 포트폴리오, 1주 스프린트 6개. S1은 문서·설계만 끝내고 종료됐고, 백엔드 셋업은 S2로 이월됐다. **S2 첫 작업은 이월분(CB-5·CB-6·CB-7)이다** — 셋업이 끝나기 전에는 S2 본 목표(도메인 API)나 문서 개편을 새로 시작하지 않는다 (S1 회고 Try #1).

S2 이월분:
- Spring Boot 프로젝트 생성 + DDD 패키지 구조
- Neon 연결 + Flyway `V1__init.sql` 적용
- GitHub 저장소 생성 (브랜치 전략·커밋 규칙은 아래 Git 규칙대로) — **CB-5·CB-7이 끝나면 Claude Code가 사용자에게 저장소를 만들라고 알린다** (그 전까지는 로컬 커밋만, 원격 백업 없음 — 2026-09-28 사용자 요청)
- 최소 CI (GitHub Actions: push·PR 시 빌드 + 테스트)
- 이월 DoD: `./gradlew bootRun`으로 앱이 뜨고 DB 연결 확인 / main push 시 CI 통과

| 스프린트 | 기간 | 목표 |
|---|---|---|
| S1 (완료, 일부 이월) | 09.21–09.27 | 요구사항 33건 확정, 설계 문서, 도메인 모델. 백엔드 뼈대·CI는 S2로 이월 |
| **S2** | 09.28–10.04 | **이월된 백엔드 셋업 + 최소 CI** → 충전·사용(+취소)으로 잔액이 정확히 변하는 것을 단위 테스트로 증명. React 첫 화면 |
| S3 | 10.05–10.11 | 인증, REST API 완성 + 통합 테스트, E2E 착수 |
| S4 | 10.12–10.18 | 프론트-백 연동, 거래 이력 조회, E2E 스모크 |
| S5 | 10.19–10.25 | 보안 점검과 탐색적 테스트, 버그 픽스 |
| S6 | 10.26–11.01 | 배포 파이프라인, 배포, 문서 마무리 |

최소 CI는 S2 이월분에서 가장 먼저 세운다 — "CI 실패 상태에서 병합 금지" 규칙은 코드가 생기는 순간부터 적용되기 때문이다. S6에는 배포 파이프라인만 남는다.

## 서브에이전트 구성 계획
`.claude/agents/`에 아래 4개를 역할별로 만들 계획이다. 현재는 backend-dev 하나만 있다. S2에 test-writer·frontend-dev를 추가할 차례지만, 이월된 백엔드 셋업을 먼저 끝낸 뒤 추가한다.

| 이름 | 역할 | 필요 시점 |
|---|---|---|
| backend-dev | Spring Boot 도메인/API 구현 | S1부터 |
| frontend-dev | React + Vite UI 구현 | S2부터 |
| test-writer | JUnit5 + Playwright 작성·실행 | S2부터 |
| code-reviewer | PR 리뷰, OWASP 보안 체크 (읽기 전용) | S3부터 — PR이 실제로 쌓이기 시작하면 |

### 서브에이전트 추가 절차
해당 스프린트가 시작되면 Claude Code에 다음처럼 요청한다: *"ECC 리포(https://github.com/affaan-m/ECC.git)를 다시 확인해서 [에이전트 이름]을 backend-dev 만들 때처럼 만들어줘."* 그러면 이 순서로 진행한다:
1. ECC 리포에서 관련 `agents/`·`commands/`·`skills/`만 얕게 가져온다 (전체 설치 아님)
2. 이 문서의 절대 금지·계층 규칙·네이밍·테스트 규칙에 맞게 다듬는다 — 원본 그대로 복사하지 않는다
3. Codex/Cursor/Gemini 등 다른 도구용 설정은 가져오지 않는다 (Claude Code 전용)
4. 함께 쓸 skill을 8~12개 선별해 `.claude/skills/`에 추가하고, 무관한 프레임워크(Quarkus, Django 등) 예시는 이 프로젝트 스택(Spring Boot/Postgres) 예시로 바꾼다
5. 기존 커맨드(`cb-review-gate`, `db-migration`, `feature-dev`)에 새 에이전트 이름을 반영해 참조를 갱신한다

**요청 전에**: 로드맵의 "현재" 표시가 실제 스프린트와 맞는지 확인한다 — 세션 시작 점검에서 스프린트 보드와 대조해 어긋나면 Claude Code가 먼저 알린다.

## 프로젝트 개요
카페/식당 선결제 잔액 관리 웹앱. 결제 처리(PG)는 범위 밖 — 이미 받은 선결제를 기록·차감만 하는 장부 도구.
1인 6주 포트폴리오. 목표 직무: SW QA / 보안 QA / 유지보수·운영 개발.
**핵심 가치는 기능이 아니라 잔액 정합성과 추적 가능성.**

## 기술 스택
- 백엔드: **Java 17** (Corretto, 로컬 기설치 버전 그대로 사용 — 21 아님) + Spring Boot 3, DDD 구조
- DB: PostgreSQL (Neon, 서버리스)
- 테스트: JUnit5(단위) + Playwright(E2E)
- 마이그레이션: Flyway
- 배포: Vercel(FE) + Render(BE) + GitHub Actions(CI/CD)

## 패키지 구조 (DDD, 도메인 최상위)
계층을 최상위로 두지 않는다. `controller / service / repository` 로 나누면 한 기능을 고칠 때 세 폴더를 오가야 하고 도메인 경계가 드러나지 않는다.

```
com.creditbook
├─ global/                  전역 공통
│   ├─ config/              SecurityConfig, JpaConfig, WebConfig
│   ├─ error/               GlobalExceptionHandler, ErrorCode, ErrorResponse
│   └─ security/            JwtProvider, JwtFilter, CurrentUser
│
├─ customer/                고객 도메인
│   ├─ domain/              Customer, CustomerRepository(interface), 도메인 예외
│   ├─ application/         CustomerService  ← @Transactional 경계
│   ├─ controller/          CustomerController, dto/{Request,Response}
│   └─ infrastructure/      CustomerJpaRepository, CustomerRepositoryImpl
│
├─ prepaid/                 선결제 도메인 (이 프로젝트의 핵심)
│   ├─ domain/              PrepaidAccount, LedgerEntry, LedgerEntryType,
│   │                       InsufficientBalanceException, InvalidAmountException
│   ├─ application/         ChargeService, UsageService, CancelService (충전 취소·사용 취소 모두)
│   ├─ controller/          ChargeController, UsageController, LedgerEntryController
│   └─ infrastructure/      PrepaidAccountJpaRepository, LedgerEntryJpaRepository
│
└─ auth/                    인증
    ├─ domain/              Employee, Role
    ├─ application/         AuthService
    ├─ controller/          AuthController
    └─ infrastructure/      EmployeeJpaRepository
```

> 참고: 거래 도메인 클래스는 테이블명과 같은 `LedgerEntry`다 (2026-09-23 결정 번복 — `Transaction`은 `jakarta.transaction.Transaction`·`@Transactional`과 혼동되므로 쓰지 않는다).

### 계층별 책임
| 계층 | 하는 일 | 하면 안 되는 일 |
|---|---|---|
| domain | 비즈니스 규칙과 불변식. 잔액 계산·검증이 여기 있다 | Spring·JPA 어노테이션 외 다른 계층 import 금지 |
| application | 유스케이스 조합, `@Transactional` 경계, 권한 확인 | 잔액 계산 로직을 직접 쓰지 않는다 |
| controller | 요청·응답 DTO 변환, 입력 형식 검증 | 비즈니스 판단 금지. 엔티티 그대로 반환 금지 |
| infrastructure | JPA 구현체, 외부 연동 | domain 인터페이스를 구현할 뿐, 규칙을 넣지 않는다 |

**단 하나의 규칙만 기억한다면** — 잔액을 더하고 빼는 모든 계산과 검사는 `PrepaidAccount` 안에 있어야 한다. 서비스가 `account.setBalance(...)`를 호출하는 순간 이 설계는 무너진다.

## 네이밍
| 대상 | 규칙 | 예 |
|---|---|---|
| 클래스 | PascalCase | `PrepaidAccount`, `ChargeService` |
| 메서드 | 동사로 시작 | `charge()`, `use()`, `cancelCharge()` |
| 불리언 반환 | `is`/`has`/`can` | `canUse(amount)`, `isActive()` |
| DTO | 용도 + Request/Response | `ChargeRequest`, `CustomerListResponse` |
| 테이블·컬럼 | 복수형 snake_case | `prepaid_accounts`, `balance_after` |
| 제약조건 | `ck_`/`ux_`/`ix_` + 테이블 + 의미 | `ck_ledger_entries_amount` |
| 트리거·함수 | `trg_`/`fn_` + 테이블 + 의미 | `trg_ledger_entries_no_truncate`, `fn_ledger_entries_append_only` |
| 테스트 메서드 | 영문 snake + `@DisplayName` 한글 | `use_fails_when_amount_exceeds_balance` |
| 도메인 용어 | 문서와 코드가 같은 단어 | 사용=`use`(차감 아님), 충전 취소=`cancelCharge()`, 사용 취소=`cancelUse()` |

## 비즈니스 규칙 (요구사항 명세 DB 기준)
| REQ | 규칙 |
|---|---|
| REQ-7 충전 취소 | **당일** 충전 건만, 사유 필수. 전액 취소만. 취소 후 잔액이 음수가 되면 거절하고 "사용 건을 먼저 취소해 주세요" 안내 |
| REQ-34 사용 취소 (Must) | **전액** 취소만, **기한 없음**, 사유 필수. `USE_CANCEL` 반제 행 추가로 잔액 복구 |
| 반제 공통 | 원본 1건당 반제 1건(`ux_ledger_entries_reverses`). 반제 대상은 **같은 계좌**, 유형 대응(`CHARGE_CANCEL`→`CHARGE`, `USE_CANCEL`→`USE`), 반제 행은 다시 반제 불가 — DB 제약으로 표현 불가하므로 도메인에서 검증 |
| REQ-4 고객 비활성화 | 잔액 0원인 고객만. 목록에서 사라지되 거래 이력은 조회됨 |
| REQ-16 권한 | 고객 비활성화는 ADMIN만 (STAFF는 403) |
| REQ-33 재활성화 | 비활성 고객에게 충전하면 자동 재활성화 |
| 충전 상한 | 1회 충전 한도 300,000원. **잔액 상한이 아니다** (여러 번 충전해 30만원 초과 가능) |

## DB 스키마 — Flyway `V1__init.sql`
테이블명은 `transactions`가 아니라 **`ledger_entries`**다 (PostgreSQL 예약어 `transaction`과의 혼동을 피하려고 2026-09-23에 확정 리네임).

```sql
CREATE EXTENSION IF NOT EXISTS pgcrypto;   -- gen_random_uuid()

CREATE TABLE store_profile (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name       VARCHAR(30) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_store_profile_name CHECK (btrim(name) <> '')
);
-- 단일 매장이므로 행은 하나뿐. 두 번째 INSERT 를 DB가 막는다
CREATE UNIQUE INDEX ux_store_profile_singleton ON store_profile ((TRUE));

CREATE TABLE employees (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    login_id             VARCHAR(50)  NOT NULL UNIQUE,
    password_hash        VARCHAR(100) NOT NULL,
    name                 VARCHAR(50)  NOT NULL,
    role                 VARCHAR(10)  NOT NULL,
    active               BOOLEAN      NOT NULL DEFAULT TRUE,
    must_change_password BOOLEAN      NOT NULL DEFAULT TRUE,
    last_login_at        TIMESTAMPTZ,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_employees_role CHECK (role IN ('ADMIN', 'STAFF'))
);
-- 활성 ADMIN 이 0명이 되는 것은 애플리케이션에서 막는다 (DB 제약으로는 표현 불가)
CREATE INDEX ix_employees_active_role ON employees (active, role);

CREATE TABLE customers (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name       VARCHAR(20)  NOT NULL,
    phone      VARCHAR(20),
    memo       VARCHAR(200),
    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_customers_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT ck_customers_phone_digits   CHECK (phone IS NULL OR phone ~ '^[0-9]{9,11}$')
);
CREATE INDEX ix_customers_name           ON customers (name);
CREATE INDEX ix_customers_phone          ON customers (phone);
CREATE INDEX ix_customers_active_created ON customers (active, created_at DESC);

CREATE TABLE prepaid_accounts (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id UUID          NOT NULL UNIQUE REFERENCES customers (id),
    balance     NUMERIC(12,0) NOT NULL DEFAULT 0,
    version     BIGINT        NOT NULL DEFAULT 0,
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_prepaid_accounts_balance_non_negative CHECK (balance >= 0)
);

CREATE TABLE ledger_entries (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    seq             BIGSERIAL     NOT NULL UNIQUE,
    account_id      UUID          NOT NULL REFERENCES prepaid_accounts (id),
    type            VARCHAR(20)   NOT NULL,
    amount          NUMERIC(12,0) NOT NULL,
    signed_amount   NUMERIC(12,0) GENERATED ALWAYS AS (
                        CASE WHEN type IN ('USE', 'CHARGE_CANCEL') THEN -amount ELSE amount END
                    ) STORED,
    balance_after   NUMERIC(12,0) NOT NULL,
    memo            VARCHAR(200),
    reverses_id     UUID          REFERENCES ledger_entries (id),
    performed_by    UUID          NOT NULL REFERENCES employees (id),
    performed_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    idempotency_key VARCHAR(64),
    CONSTRAINT ck_ledger_entries_type     CHECK (type IN ('CHARGE', 'USE', 'CHARGE_CANCEL', 'USE_CANCEL')),
    CONSTRAINT ck_ledger_entries_amount   CHECK (amount > 0),
    CONSTRAINT ck_ledger_entries_balance  CHECK (balance_after >= 0),
    CONSTRAINT ck_ledger_entries_reverses CHECK (
        (type IN ('CHARGE_CANCEL', 'USE_CANCEL') AND reverses_id IS NOT NULL)
        OR (type IN ('CHARGE', 'USE') AND reverses_id IS NULL)
    )
);
CREATE INDEX ix_ledger_entries_account_seq       ON ledger_entries (account_id, seq DESC);
CREATE INDEX ix_ledger_entries_account_performed ON ledger_entries (account_id, performed_at DESC);
CREATE UNIQUE INDEX ux_ledger_entries_idem       ON ledger_entries (idempotency_key)
    WHERE idempotency_key IS NOT NULL;
CREATE UNIQUE INDEX ux_ledger_entries_reverses   ON ledger_entries (reverses_id)
    WHERE reverses_id IS NOT NULL;   -- 같은 건을 두 번 반제 못 함

CREATE TABLE phone_access_logs (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id UUID        NOT NULL REFERENCES customers (id),
    accessed_by UUID        NOT NULL REFERENCES employees (id),
    accessed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_phone_access_logs_customer ON phone_access_logs (customer_id, accessed_at DESC);

-- append-only: ledger_entries 는 INSERT 만 허용한다. 정정은 반제 행(CHARGE_CANCEL/USE_CANCEL)으로만 한다.
-- 코드 리뷰를 통과한 UPDATE/DELETE 가 배포돼도 DB 가 마지막 방어선이 된다 (절대 금지 1번)
CREATE FUNCTION fn_ledger_entries_append_only() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'ledger_entries is append-only: % is not allowed', TG_OP
        USING ERRCODE = 'restrict_violation',
              HINT = '정정은 CHARGE_CANCEL/USE_CANCEL 반제 행을 INSERT 한다';
END;
$$;

CREATE TRIGGER trg_ledger_entries_no_update_delete
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION fn_ledger_entries_append_only();

-- TRUNCATE 는 행 트리거를 거치지 않으므로 문장 트리거로 따로 막는다
CREATE TRIGGER trg_ledger_entries_no_truncate
    BEFORE TRUNCATE ON ledger_entries
    FOR EACH STATEMENT EXECUTE FUNCTION fn_ledger_entries_append_only();
```

- `V1__init.sql`은 아직 어느 DB에도 적용되지 않았다. 적용 전까지는 V2를 만들지 말고 V1을 직접 고친다.
- `signed_amount` CASE식은 `USE_CANCEL`을 `ELSE amount`(+)로 처리한다 — 사용 취소는 잔액을 되돌리므로 의도된 동작이다.
- **append-only는 DB에서도 막는다 — 트리거로 확정** (2026-09-28 사용자 승인, CB-7): `trg_ledger_entries_no_update_delete`(행 단위 UPDATE·DELETE)와 `trg_ledger_entries_no_truncate`(문장 단위 TRUNCATE)가 `fn_ledger_entries_append_only()`를 호출해 SQLSTATE `23001`(restrict_violation)로 거절한다. REVOKE를 택하지 않은 이유: 앱이 테이블 소유자(`neondb_owner`)로 접속하므로 소유자는 회수된 권한을 스스로 되돌릴 수 있다. 한계: 소유자는 트리거를 끄거나 지울 수 있으므로 이 트리거는 애플리케이션 버그를 막는 장치다. 소유자가 아닌 앱 전용 계정 분리는 S5·S6 보안 강화 후보. SQL은 `V1__init.sql` 끝부분이 확정본이다.
- **외래 키 인덱스 결정** (2026-09-28): `ledger_entries.performed_by`, `phone_access_logs.accessed_by`에는 인덱스를 두지 않는다. 직원은 삭제하지 않고(`active` 플래그) 직원별 조회 요구사항도 없어서, FK 인덱스가 쓰이는 경로가 없다. 직원별 거래 조회 요구사항이 생기면 그때 추가한다.
- 정합성 점검 쿼리(잔액 대사, `balance_after` 누적합, 반제 무결성·유형 불일치)는 스키마 정의서 §6이 확정본이다. 모두 0행이어야 정상이며, 정합성 테스트와 배포 후 스모크에 그대로 쓴다.

## 절대 금지 (예외 없음, 하나라도 보이면 머지하지 않는다)
1. `ledger_entries` 테이블에 UPDATE / DELETE — 정정은 반제 거래로만. 코드 리뷰뿐 아니라 DB 권한(REVOKE)·트리거로도 차단
2. 금액에 `double` / `float`
3. 잔액 컬럼을 애플리케이션 밖에서 직접 UPDATE
4. 엔티티를 컨트롤러 응답으로 반환 (DTO 경유)
5. 시크릿·DB 비밀번호 하드코딩 (`.env`, `application-local.yml` 은 `.gitignore`)
6. `@Enumerated(EnumType.ORDINAL)` (STRING만 사용)
7. 테스트 없이 도메인 로직 변경
8. CI 실패 상태에서 병합

## JPA 매핑 주의사항
- `signed_amount`: 생성 컬럼이므로 `@Column(insertable = false, updatable = false)`로 읽기 전용
- `seq`: BIGSERIAL → DB가 채우므로 동일하게 insertable=false
- `balance`: `BigDecimal`, scale=0 유지, `add`/`subtract`만 사용 (`divide` 금지)
- `version`: `@Version` — 동시 차감 시 `OptimisticLockException` → 409로 변환
- 시각 컬럼: `OffsetDateTime`/`Instant` 사용 (`LocalDateTime` 금지 — 시간대 유실)
- `type`: `@Enumerated(EnumType.STRING)`

## Git / 커밋 규칙
- GitHub Flow만 사용 (develop 브랜치 없음). `main`은 항상 배포 가능, PR로만 병합
- Jira 프로젝트 키는 **`CB`**다 (이전 `KAN`에서 확정 변경). S1~S6 작업 이슈는 CB-4~CB-23으로 생성돼 있다
- 브랜치: `feature/CB-42-charge-api`, `fix/CB-57-balance-boundary` — Jira 키 포함
- 커밋: `<type>(<Jira 키>): <한 줄 요약>` — type은 feat/fix/test/refactor/docs/chore
- 혼자 작업해도 PR을 거친다 (CI 통과 확인 + 변경 이력 보존 목적)

## 테스트 규칙
- Given-When-Then 세 블록, `@DisplayName`은 한글로 요구사항 인수조건 문장 그대로
- 단위 테스트에 스프링 컨텍스트를 띄우지 않는다 (도메인 규칙은 순수 객체로 검증)
- 경계값은 `@ParameterizedTest`로 한 곳에 모은다
- BigDecimal 비교는 `isEqualByComparingTo` (`equals` 금지 — scale까지 비교함)
- 통합 테스트는 Testcontainers (H2는 GENERATED 컬럼·부분 인덱스 동작이 달라 운영과 다르게 나옴)
- 테스트는 실행 순서에 의존하지 않는다
- 버그 수정은 재현 테스트 먼저 작성
- **정합성 증명 테스트 3종**은 반드시 갖춘다 (S2 완료 기준):
  1. 대사 — 시나리오 실행 후 스키마 정의서 §6 쿼리가 모두 0행
  2. 동시성 — 같은 계좌 동시 차감 시 초과분은 409, 최종 잔액 = 성공 거래 합계
  3. append-only 차단 — 애플리케이션 계정으로 `ledger_entries` UPDATE/DELETE 시도가 실패
- 도메인 테스트에는 `@DisplayName`에 더해 `@Tag("REQ-xxx")` — 요구사항 명세 DB의 REQ ID와 연결
- CI에 JaCoCo 커버리지 리포트, `domain` 패키지에는 PIT 뮤테이션 테스트

## Neon 연결
1. Neon 프로젝트의 connection string을 발급받는다 (Pooled connection 권장)
2. `application-local.yml` 또는 `.env`에 `SPRING_DATASOURCE_URL` / `USERNAME` / `PASSWORD`로 저장 — **절대 커밋하지 않는다** (`.gitignore` 확인)
3. Flyway가 `V1__init.sql`을 첫 실행 시 자동 적용

## 설정값 (application.yml, 하드코딩 금지)
- `creditbook.charge.max-amount: 300000` — **1회** 충전 한도(잔액 상한 아님). 경계값 테스트가 상한을 바꿔가며 돌아야 하므로 설정값으로 둔다
- `creditbook.memo.presets: [음료, 베이커리, 원두, 디저트, MD, 기타]` — 메모 문구 칩. 매장마다 달라 설정값으로 둔다(테이블 없음)

## 로그 정책 (운영 점검 가이드 §6 요약)
- 잔액 변동: INFO — 계좌 ID·유형·금액·변동 후 잔액·실행자. 고객 이름·연락처는 남기지 않는다
- 잔액 부족·권한 거부: WARN (정상 동작이므로 ERROR 아님)
- 예외: ERROR, 스택트레이스는 로그에만 — 응답에는 절대 포함하지 않는다
- 모든 로그 라인에 요청 correlation id

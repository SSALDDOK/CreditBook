---
name: database-migrations
description: PostgreSQL/Flyway 스키마·데이터 마이그레이션 모범 사례 — 안전한 컬럼 추가, 무중단 인덱스 생성, expand-contract 패턴, 대량 데이터 백필. CreditBook은 Flyway를 쓰므로 원본 ECC 스킬에서 Prisma/Drizzle/Kysely/Django/golang-migrate 섹션은 제거하고 Flyway 워크플로로 대체했다. 스키마·데이터 마이그레이션 작성, 롤백 계획, 무중단 배포를 고민할 때 사용.
metadata:
  origin: ECC (trimmed + Flyway section added for CreditBook)
---

# 데이터베이스 마이그레이션 패턴 (PostgreSQL / Flyway)

프로덕션 시스템을 위한 안전하고 되돌릴 수 있는 스키마 변경 패턴.

## 언제 쓰는가

- 테이블 생성/변경
- 컬럼·인덱스 추가/삭제
- 데이터 마이그레이션(백필, 변환) 실행
- 무중단 스키마 변경 계획

## 핵심 원칙

1. **모든 변경은 마이그레이션이다** — 프로덕션 DB를 수동으로 건드리지 않는다
2. **프로덕션 마이그레이션은 앞으로만 간다(forward-only)** — 롤백은 되돌리는 새 마이그레이션으로 한다
3. **스키마 변경과 데이터 변경은 분리한다** — 한 마이그레이션에 DDL과 DML을 섞지 않는다
4. **운영 규모 데이터로 테스트한다** — 100행에서 되는 마이그레이션이 1천만 행에서는 테이블을 잠글 수 있다
5. **배포된 마이그레이션은 불변이다** — 이미 프로덕션에서 실행된 `V__` 파일은 절대 수정하지 않는다(체크섬 오류)

CreditBook에서는 여기에 하나가 더 붙는다: **`ledger_entries`는 append-only다.** 이 테이블의 기존 행을 바꾸는 UPDATE는 어떤 마이그레이션에도 등장해서는 안 된다.

## 마이그레이션 안전 체크리스트

- [ ] 큰 테이블에 풀 테이블 락을 거는 작업이 없는가 (concurrent 연산 사용)
- [ ] 새 컬럼에 기본값이 있거나 NULL 허용인가 (기본값 없이 NOT NULL 추가 금지)
- [ ] 인덱스는 CONCURRENTLY로 생성하는가 (기존 테이블 대상)
- [ ] 데이터 백필은 스키마 변경과 별도 마이그레이션인가
- [ ] 롤백 계획이 문서화됐는가 (forward-only이므로 "되돌리는 다음 마이그레이션"이 계획인가)

## PostgreSQL 패턴

### 컬럼 안전하게 추가하기

```sql
-- 좋음: NULL 허용, 락 없음
ALTER TABLE customers ADD COLUMN avatar_url TEXT;

-- 좋음: 기본값 있는 컬럼 (Postgres 11+ 는 즉시 반영, 재작성 없음)
ALTER TABLE customers ADD COLUMN marketing_opt_in BOOLEAN NOT NULL DEFAULT false;

-- 나쁨: 기존 테이블에 기본값 없이 NOT NULL (풀 재작성 유발)
ALTER TABLE customers ADD COLUMN grade TEXT NOT NULL;
```

### 무중단으로 인덱스 추가하기

```sql
-- 나쁨: 큰 테이블에서 쓰기를 막음
CREATE INDEX idx_ledger_entries_memo ON ledger_entries (memo);

-- 좋음: 논블로킹, 동시 쓰기 허용
CREATE INDEX CONCURRENTLY idx_ledger_entries_memo ON ledger_entries (memo);
-- 주의: CONCURRENTLY는 트랜잭션 블록 안에서 실행 불가.
-- Flyway 기본 마이그레이션은 하나의 트랜잭션으로 실행되므로,
-- CONCURRENTLY가 필요하면 해당 마이그레이션에 `-- flyway:executeInTransaction=false`를 지정한다.
```

### 컬럼 이름 바꾸기 (무중단, expand-contract)

운영에서 직접 rename하지 않는다:

```sql
-- 1단계: 새 컬럼 추가 (V{n})
ALTER TABLE employees ADD COLUMN display_name VARCHAR(50);

-- 2단계: 백필 (V{n+1}, 데이터 마이그레이션)
UPDATE employees SET display_name = name WHERE display_name IS NULL;

-- 3단계: 애플리케이션 코드가 새 컬럼을 읽고 쓰도록 배포

-- 4단계: 이전 컬럼에 쓰기를 멈춘 뒤 삭제 (V{n+2})
ALTER TABLE employees DROP COLUMN name;
```

### 대량 데이터 마이그레이션

```sql
-- 나쁨: 한 트랜잭션에서 전체 행 UPDATE (테이블 락)
UPDATE customers SET phone = regexp_replace(phone, '[^0-9]', '', 'g');

-- 좋음: 배치 업데이트 + 진행 상황 로그
DO $$
DECLARE
  batch_size INT := 5000;
  rows_updated INT;
BEGIN
  LOOP
    UPDATE customers
    SET phone = regexp_replace(phone, '[^0-9]', '', 'g')
    WHERE id IN (
      SELECT id FROM customers
      WHERE phone ~ '[^0-9]'
      LIMIT batch_size
      FOR UPDATE SKIP LOCKED
    );
    GET DIAGNOSTICS rows_updated = ROW_COUNT;
    RAISE NOTICE '% 행 갱신', rows_updated;
    EXIT WHEN rows_updated = 0;
    COMMIT;
  END LOOP;
END $$;
```

## Flyway 워크플로 (CreditBook 기본)

CreditBook은 Flyway Gradle 플러그인을 쓰지 않는다. `spring-boot-starter-flyway`가 **앱 기동 시** 마이그레이션을 검증·적용한다.

| 하고 싶은 것 | 방법 |
|---|---|
| 적용 이력 확인 | Neon에서 `SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;` |
| 적용 전 SQL 동작 확인 | **Neon 롤백 전용 트랜잭션** — jshell + JDBC jar로 direct 연결, `setAutoCommit(false)` → SQL 실행 → 시나리오 확인 → `rollback()` → 잔여 객체 없음 확인 (`/db-migration` 5단계) |
| 자동 테스트 | Testcontainers 통합 테스트 — 테스트 컨텍스트가 뜰 때 Flyway가 빈 PostgreSQL에 전체 마이그레이션을 적용하므로, 파일명·문법 오류는 여기서 드러난다 |
| 실제 적용 | `cd backend && ./gradlew bootRun` — **되돌릴 수 없으므로 적용 시점은 사용자와 정한다** |

- 파일명: `V{n}__snake_case_description.sql`, `n`은 항상 증가
- **이미 적용된 파일은 절대 수정하지 않는다** — 다음 기동 때 Flyway 검증이 체크섬 불일치로 실패한다. 정정이 필요하면 새 버전 파일을 추가한다. 단, **아직 어느 DB에도 적용되지 않은 파일**(CLAUDE.md에 "미적용"으로 적힌 V1 등)은 직접 고친다
- `flyway_schema_history` 테이블이 적용 이력을 관리한다 — 이 테이블을 수동으로 건드리지 않는다
- 검증은 Testcontainers 기반 통합 테스트로 한다 (H2는 이 프로젝트의 `GENERATED` 컬럼·부분 유니크 인덱스 동작을 재현하지 못한다)

## 무중단 마이그레이션 전략 (Expand-Contract)

```
1단계: 확장(EXPAND)
  - 새 컬럼/테이블 추가 (NULL 허용 또는 기본값)
  - 배포: 앱이 기존 컬럼과 새 컬럼 모두에 씀
  - 기존 데이터 백필

2단계: 이전(MIGRATE)
  - 배포: 앱이 새 컬럼에서 읽고, 양쪽에 씀
  - 데이터 일관성 검증

3단계: 축소(CONTRACT)
  - 배포: 앱이 새 컬럼만 사용
  - 별도 마이그레이션에서 이전 컬럼/테이블 삭제
```

## 안티패턴

| 안티패턴 | 문제 | 대안 |
|---|---|---|
| 프로덕션에서 수동 SQL 실행 | 감사 추적 없음, 재현 불가 | 항상 Flyway 마이그레이션 파일 사용 |
| 배포된 마이그레이션 수정 | 환경 간 드리프트 유발, 체크섬 오류 | 새 마이그레이션 파일 생성 |
| 기본값 없이 NOT NULL 추가 | 테이블 락, 전체 행 재작성 | NULL 허용으로 추가 → 백필 → 제약 추가 |
| 큰 테이블에 인라인 인덱스 | 빌드 중 쓰기 차단 | `CREATE INDEX CONCURRENTLY` |
| 스키마+데이터 변경을 한 마이그레이션에 | 롤백 어려움, 긴 트랜잭션 | 마이그레이션 분리 |
| 코드 제거 전에 컬럼 삭제 | 컬럼 누락으로 애플리케이션 에러 | 코드 먼저 제거 → 다음 배포에서 컬럼 삭제 |
| `ledger_entries` 행을 UPDATE로 정정 | append-only 불변식 위반, CreditBook 절대 금지 1번 | `CHARGE_CANCEL`/`USE_CANCEL` 반제 행 INSERT |

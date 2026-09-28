---
name: postgres-patterns
description: "PostgreSQL 쿼리 최적화·스키마 설계·인덱싱 패턴 빠른 참조 (Supabase 모범 사례 기반). CreditBook은 단일 매장용이라 RLS/멀티테넌시는 해당 없음 — 참고용으로만 남겨둠. SQL/마이그레이션 작성, 스키마 설계, 느린 쿼리 문제 해결 시 사용."
metadata:
  origin: ECC
---

# PostgreSQL 패턴

PostgreSQL 모범 사례 빠른 참조. CreditBook 구현 시에는 `backend-dev` 에이전트가 이 내용을 함께 참고한다.

## 언제 쓰는가

- SQL 쿼리·Flyway 마이그레이션 작성
- 스키마 설계
- 느린 쿼리 문제 해결
- 커넥션 풀링 설정

## CreditBook에 해당하지 않는 부분

- **RLS(Row Level Security)**: CreditBook은 단일 매장용 내부 장부 도구로, `auth.uid()`(Supabase 인증) 기반 RLS 정책이 없다. 접근 제어는 Spring Security(JWT) + 애플리케이션 계층 권한 확인(ADMIN/STAFF)으로 한다. 아래 RLS 예시는 다른 멀티테넌시 프로젝트를 위한 참고용이다.
- **`ALTER SYSTEM SET ...`**: Neon은 서버리스 관리형 Postgres라 슈퍼유저 권한으로 서버 설정을 직접 바꿀 수 없다. 커넥션 풀/타임아웃은 Neon 콘솔 또는 Spring `HikariCP` 설정(`spring.datasource.hikari.*`)으로 조정한다.
- **금액 타입**: 아래 표는 `numeric(10,2)`를 예로 들지만 CreditBook은 원화만 다루므로 `NUMERIC(12,0)`(소수점 없음)을 쓴다 — `CLAUDE.md`의 DB 스키마 참고.

## 빠른 참조

### 인덱스 요약표

| 쿼리 패턴 | 인덱스 종류 | 예시 |
|--------------|------------|---------|
| `WHERE col = value` | B-tree (기본) | `CREATE INDEX idx ON t (col)` |
| `WHERE col > value` | B-tree | `CREATE INDEX idx ON t (col)` |
| `WHERE a = x AND b > y` | 복합 인덱스 | `CREATE INDEX idx ON t (a, b)` |
| `WHERE jsonb @> '{}'` | GIN | `CREATE INDEX idx ON t USING gin (col)` |
| `WHERE tsv @@ query` | GIN | `CREATE INDEX idx ON t USING gin (col)` |
| 시계열 범위 조회 | BRIN | `CREATE INDEX idx ON t USING brin (col)` |

### 데이터 타입 빠른 참조

| 용도 | 올바른 타입 | 피할 것 |
|----------|-------------|-------|
| ID | `bigint` (CreditBook은 `UUID DEFAULT gen_random_uuid()`) | `int` |
| 문자열 | `text` | `varchar(255)` |
| 시각 | `timestamptz` | `timestamp` |
| 금액 | `numeric(10,2)` (CreditBook은 `NUMERIC(12,0)`) | `float` |
| 플래그 | `boolean` | `varchar`, `int` |

### 자주 쓰는 패턴

**복합 인덱스 컬럼 순서:**
```sql
-- 동등 비교 컬럼을 먼저, 범위 비교 컬럼을 뒤에
CREATE INDEX idx ON orders (status, created_at);
-- 이런 조건에 쓰인다: WHERE status = 'pending' AND created_at > '2024-01-01'
```

**커버링 인덱스:**
```sql
CREATE INDEX idx ON users (email) INCLUDE (name, created_at);
-- SELECT email, name, created_at 시 테이블 조회를 생략한다
```

**부분 인덱스(Partial Index):**
```sql
CREATE INDEX idx ON users (email) WHERE deleted_at IS NULL;
-- 활성 사용자만 담아 인덱스가 작아진다
```

**RLS 정책 (최적화) — CreditBook 해당 없음:**
```sql
CREATE POLICY policy ON orders
  USING ((SELECT auth.uid()) = user_id);  -- SELECT로 감쌀 것!
```

**UPSERT:**
```sql
INSERT INTO settings (user_id, key, value)
VALUES (123, 'theme', 'dark')
ON CONFLICT (user_id, key)
DO UPDATE SET value = EXCLUDED.value;
```

**커서 페이지네이션:**
```sql
SELECT * FROM products WHERE id > $last_id ORDER BY id LIMIT 20;
-- O(1). OFFSET 방식은 O(n)
```

**큐 처리:**
```sql
UPDATE jobs SET status = 'processing'
WHERE id = (
  SELECT id FROM jobs WHERE status = 'pending'
  ORDER BY created_at LIMIT 1
  FOR UPDATE SKIP LOCKED
) RETURNING *;
```

### 안티패턴 탐지

```sql
-- 인덱스 없는 외래 키 찾기
SELECT conrelid::regclass, a.attname
FROM pg_constraint c
JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY(c.conkey)
WHERE c.contype = 'f'
  AND NOT EXISTS (
    SELECT 1 FROM pg_index i
    WHERE i.indrelid = c.conrelid AND a.attnum = ANY(i.indkey)
  );

-- 느린 쿼리 찾기
SELECT query, mean_exec_time, calls
FROM pg_stat_statements
WHERE mean_exec_time > 100
ORDER BY mean_exec_time DESC;

-- 테이블 팽창(bloat) 확인
SELECT relname, n_dead_tup, last_vacuum
FROM pg_stat_user_tables
WHERE n_dead_tup > 1000
ORDER BY n_dead_tup DESC;
```

### 설정 템플릿 (Neon에서는 ALTER SYSTEM 불가 — 참고용)

```sql
-- 연결 수 제한 (RAM에 맞게 조정)
ALTER SYSTEM SET max_connections = 100;
ALTER SYSTEM SET work_mem = '8MB';

-- 타임아웃
ALTER SYSTEM SET idle_in_transaction_session_timeout = '30s';
ALTER SYSTEM SET statement_timeout = '30s';

-- 모니터링
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;

-- 보안 기본값
REVOKE ALL ON SCHEMA public FROM public;

SELECT pg_reload_conf();
```

## 관련 항목

- Agent: `backend-dev` — 스키마/마이그레이션 구현 및 자체 검토
- Skill: `database-migrations` — Flyway 마이그레이션 전용 가이드 (같은 `.claude/skills/`)

---

*Supabase Agent Skills 기반 (출처: Supabase 팀, MIT 라이선스)*

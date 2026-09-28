---
name: postgres-patterns
description: "PostgreSQL 쿼리 최적화·스키마 설계·인덱싱 패턴 빠른 참조 (Supabase best practices 기반). CreditBook은 단일 매장용이라 RLS/멀티테넌시는 해당 없음 — 참고용으로만 남겨둠. SQL/마이그레이션 작성, 스키마 설계, 느린 쿼리 troubleshooting 시 사용."
metadata:
  origin: ECC
---

# PostgreSQL Patterns

PostgreSQL 모범 사례 빠른 참조. CreditBook 구현 시에는 `backend-dev` 에이전트가 이 내용을 함께 참고한다.

## When to Activate

- SQL 쿼리·Flyway 마이그레이션 작성
- 스키마 설계
- 느린 쿼리 troubleshooting
- 커넥션 풀링 설정

## CreditBook에 해당하지 않는 부분

- **RLS(Row Level Security)**: CreditBook은 단일 매장용 내부 장부 도구로, `auth.uid()`(Supabase 인증) 기반 RLS 정책이 없다. 접근 제어는 Spring Security(JWT) + 애플리케이션 계층 권한 확인(ADMIN/STAFF)으로 한다. 아래 RLS 예시는 다른 멀티테넌시 프로젝트를 위한 참고용이다.
- **`ALTER SYSTEM SET ...`**: Neon은 서버리스 관리형 Postgres라 슈퍼유저 권한으로 서버 설정을 직접 바꿀 수 없다. 커넥션 풀/타임아웃은 Neon 콘솔 또는 Spring `HikariCP` 설정(`spring.datasource.hikari.*`)으로 조정한다.
- **금액 타입**: 아래 표는 `numeric(10,2)`를 예로 들지만 CreditBook은 원화만 다루므로 `NUMERIC(12,0)`(소수점 없음)을 쓴다 — `CLAUDE.md`의 DB 스키마 참고.

## Quick Reference

### Index Cheat Sheet

| Query Pattern | Index Type | Example |
|--------------|------------|---------|
| `WHERE col = value` | B-tree (default) | `CREATE INDEX idx ON t (col)` |
| `WHERE col > value` | B-tree | `CREATE INDEX idx ON t (col)` |
| `WHERE a = x AND b > y` | Composite | `CREATE INDEX idx ON t (a, b)` |
| `WHERE jsonb @> '{}'` | GIN | `CREATE INDEX idx ON t USING gin (col)` |
| `WHERE tsv @@ query` | GIN | `CREATE INDEX idx ON t USING gin (col)` |
| Time-series ranges | BRIN | `CREATE INDEX idx ON t USING brin (col)` |

### Data Type Quick Reference

| Use Case | Correct Type | Avoid |
|----------|-------------|-------|
| IDs | `bigint` | `int`, random UUID |
| Strings | `text` | `varchar(255)` |
| Timestamps | `timestamptz` | `timestamp` |
| Money | `numeric(10,2)` | `float` |
| Flags | `boolean` | `varchar`, `int` |

### Common Patterns

**Composite Index Order:**
```sql
-- Equality columns first, then range columns
CREATE INDEX idx ON orders (status, created_at);
-- Works for: WHERE status = 'pending' AND created_at > '2024-01-01'
```

**Covering Index:**
```sql
CREATE INDEX idx ON users (email) INCLUDE (name, created_at);
-- Avoids table lookup for SELECT email, name, created_at
```

**Partial Index:**
```sql
CREATE INDEX idx ON users (email) WHERE deleted_at IS NULL;
-- Smaller index, only includes active users
```

**RLS Policy (Optimized):**
```sql
CREATE POLICY policy ON orders
  USING ((SELECT auth.uid()) = user_id);  -- Wrap in SELECT!
```

**UPSERT:**
```sql
INSERT INTO settings (user_id, key, value)
VALUES (123, 'theme', 'dark')
ON CONFLICT (user_id, key)
DO UPDATE SET value = EXCLUDED.value;
```

**Cursor Pagination:**
```sql
SELECT * FROM products WHERE id > $last_id ORDER BY id LIMIT 20;
-- O(1) vs OFFSET which is O(n)
```

**Queue Processing:**
```sql
UPDATE jobs SET status = 'processing'
WHERE id = (
  SELECT id FROM jobs WHERE status = 'pending'
  ORDER BY created_at LIMIT 1
  FOR UPDATE SKIP LOCKED
) RETURNING *;
```

### Anti-Pattern Detection

```sql
-- Find unindexed foreign keys
SELECT conrelid::regclass, a.attname
FROM pg_constraint c
JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY(c.conkey)
WHERE c.contype = 'f'
  AND NOT EXISTS (
    SELECT 1 FROM pg_index i
    WHERE i.indrelid = c.conrelid AND a.attnum = ANY(i.indkey)
  );

-- Find slow queries
SELECT query, mean_exec_time, calls
FROM pg_stat_statements
WHERE mean_exec_time > 100
ORDER BY mean_exec_time DESC;

-- Check table bloat
SELECT relname, n_dead_tup, last_vacuum
FROM pg_stat_user_tables
WHERE n_dead_tup > 1000
ORDER BY n_dead_tup DESC;
```

### Configuration Template

```sql
-- Connection limits (adjust for RAM)
ALTER SYSTEM SET max_connections = 100;
ALTER SYSTEM SET work_mem = '8MB';

-- Timeouts
ALTER SYSTEM SET idle_in_transaction_session_timeout = '30s';
ALTER SYSTEM SET statement_timeout = '30s';

-- Monitoring
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;

-- Security defaults
REVOKE ALL ON SCHEMA public FROM public;

SELECT pg_reload_conf();
```

## Related

- Agent: `backend-dev` — 스키마/마이그레이션 구현 및 자체 검토
- Skill: `database-migrations` — Flyway 마이그레이션 전용 가이드 (같은 `.claude/skills/`)

---

*Based on Supabase Agent Skills (credit: Supabase team) (MIT License)*

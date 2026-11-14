-- 로컬 Docker DB 전용 개발용 직원 시드. 운영(Neon)에 실행 금지.
--
-- Flyway 경로(db/migration) 밖에 두어 자동 적용되지 않는다. 로컬 DB 에 V5 까지 적용된 뒤(bootRun 한 번) 리포 루트에서 손으로 실행한다.
-- 비밀번호는 이 파일에 두지 않는다(공개 리포) — 실행할 때 psql 변수로 넘기면 DB 가 그 자리에서 BCrypt 해시로 바꿔 저장한다:
--   docker cp backend/dev/seed-local.sql creditbook-db:/tmp/seed-local.sql
--   docker exec creditbook-db psql -U creditbook -d creditbook -v ON_ERROR_STOP=1 -v owner_pw=<사장 비밀번호> -v staff_pw=<직원 비밀번호> -f /tmp/seed-local.sql
--   (PowerShell 의 Get-Content 파이프는 한글 이름을 깨뜨릴 수 있어 파일 복사 방식을 쓴다)
-- 변수를 빠뜨리면 psql 이 오류로 멈춘다. 여러 번 실행해도 같은 아이디가 있으면 건너뛴다(비밀번호도 바뀌지 않는다).
--
-- 만들어지는 계정: ADMIN 아이디 owner, STAFF 아이디 staff — 둘 다 같은 매장에 속한다(V5, 직원은 매장이 있어야 한다).
-- 매장: owner 가 이미 있으면 owner 의 매장을 쓰고(V5 가 기존 직원을 붙인 '기존 매장' 포함), 없으면 '개발매장' 을 새로 만든다.
-- 해시는 pgcrypto 의 crypt(..., gen_salt('bf', 10)) — 서버의 BCryptPasswordEncoder 기본값(비용 계수 10, $2a$)과 같은 형식이다.
-- pgcrypto 확장은 로컬 DB 에만 켠다(마이그레이션이 아니다).
-- 로컬에서 매번 비밀번호를 바꾸지 않도록 must_change_password 는 FALSE 로 둔다.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

WITH existing_store AS (
    SELECT store_id AS id FROM employees WHERE login_id = 'owner'
), new_store AS (
    INSERT INTO stores (name)
    SELECT '개발매장' WHERE NOT EXISTS (SELECT 1 FROM existing_store)
    RETURNING id
), target_store AS (
    SELECT id FROM existing_store
    UNION ALL
    SELECT id FROM new_store
)
INSERT INTO employees (store_id, login_id, password_hash, name, role, must_change_password)
SELECT t.id, v.login_id, v.password_hash, v.name, v.role, FALSE
  FROM target_store t
 CROSS JOIN (VALUES
    ('owner', crypt(:'owner_pw', gen_salt('bf', 10)), '개발사장', 'ADMIN'),
    ('staff', crypt(:'staff_pw', gen_salt('bf', 10)), '개발직원', 'STAFF')
 ) AS v (login_id, password_hash, name, role)
ON CONFLICT (login_id) DO NOTHING;

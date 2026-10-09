-- 로컬 Docker DB 전용 개발용 직원 시드. 운영(Neon)에 실행 금지.
--
-- Flyway 경로(db/migration) 밖에 두어 자동 적용되지 않는다. 로컬 DB 에 V3 까지 적용된 뒤(bootRun 한 번) 리포 루트에서 손으로 실행한다.
-- 비밀번호는 이 파일에 두지 않는다(공개 리포) — 실행할 때 psql 변수로 넘기면 DB 가 그 자리에서 BCrypt 해시로 바꿔 저장한다:
--   docker cp backend/dev/seed-local.sql creditbook-db:/tmp/seed-local.sql
--   docker exec creditbook-db psql -U creditbook -d creditbook -v ON_ERROR_STOP=1 -v owner_pw=<사장 비밀번호> -v staff_pw=<직원 비밀번호> -f /tmp/seed-local.sql
--   (PowerShell 의 Get-Content 파이프는 한글 이름을 깨뜨릴 수 있어 파일 복사 방식을 쓴다)
-- 변수를 빠뜨리면 psql 이 오류로 멈춘다. 여러 번 실행해도 같은 아이디가 있으면 건너뛴다(비밀번호도 바뀌지 않는다).
--
-- 만들어지는 계정: ADMIN 아이디 owner, STAFF 아이디 staff
-- 해시는 pgcrypto 의 crypt(..., gen_salt('bf', 10)) — 서버의 BCryptPasswordEncoder 기본값(비용 계수 10, $2a$)과 같은 형식이다.
-- pgcrypto 확장은 로컬 DB 에만 켠다(마이그레이션이 아니다).
-- 로컬에서 매번 비밀번호를 바꾸지 않도록 must_change_password 는 FALSE 로 둔다.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

INSERT INTO employees (login_id, password_hash, name, role, must_change_password)
VALUES
    ('owner', crypt(:'owner_pw', gen_salt('bf', 10)), '개발사장', 'ADMIN', FALSE),
    ('staff', crypt(:'staff_pw', gen_salt('bf', 10)), '개발직원', 'STAFF', FALSE)
ON CONFLICT (login_id) DO NOTHING;

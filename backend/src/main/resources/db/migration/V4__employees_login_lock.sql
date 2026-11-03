-- 로그인 잠금 칸 (REQ-15, CB-15).
-- 있는 계정만 계정 단위로 연속 로그인 실패를 센다. 기준 횟수를 채우면 정해진 시간 동안 잠그고,
-- 로그인에 성공하거나 잠금이 끝난 뒤에는 횟수와 잠금 끝 시각을 다시 0·NULL 로 되돌린다.
--
-- failed_login_count: 연속 실패 횟수. 기존 직원은 0 에서 시작한다.
-- locked_until      : 잠금이 끝나는 시각. NULL = 잠기지 않음. 이 시각이 지나면 잠금이 풀린 것으로 본다.
--
-- 횟수의 상한 CHECK 는 두지 않는다: 잠금 기준 횟수(기본 5회)는 설정값(creditbook.auth.login-lock.max-failures)이라
-- DB 에 숫자를 박으면 설정을 바꿀 때 마이그레이션도 함께 바꿔야 한다. 음수만 막는다.
-- 직원 행이 바뀌는 것이므로 append-only 대상(ledger_entries)과는 관계없다.
ALTER TABLE employees
    ADD COLUMN failed_login_count INTEGER     NOT NULL DEFAULT 0,
    ADD COLUMN locked_until       TIMESTAMPTZ,
    ADD CONSTRAINT ck_employees_failed_login_count CHECK (failed_login_count >= 0);

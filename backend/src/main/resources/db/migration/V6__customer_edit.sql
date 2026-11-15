-- 고객 정보 수정 (REQ-3, CB-52).
-- 1) 동시 수정 방지: customers.version — 화면이 받은 버전을 수정 요청에 돌려보내고, 늦은 쪽은 409 CONCURRENT_MODIFICATION.
-- 2) 변경 기록: customer_change_logs — 누가·언제·어느 칸을 바꿨는지만 남긴다. 값(이전·이후)은 저장하지 않는다.
--    이 표는 지울 수 없는 기록이라, 개인정보(이름·연락처) 값을 넣으면 나중에 지울 방법이 없다.
--
-- append-only(ledger_entries): 이 파일은 ledger_entries 를 건드리지 않는다.
-- customers.version 은 상수 DEFAULT 로 추가한다 — PostgreSQL 11+ 는 메타데이터로만 처리해 기존 행을 다시 쓰지 않는다.

-- 1. 고객 버전 (JPA @Version) ---------------------------------------------------------------
-- DEFAULT 는 남겨 둔다: 기존 행·JPA 밖 INSERT(테스트 준비 SQL)도 0 에서 시작한다.
ALTER TABLE customers ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE customers ADD CONSTRAINT ck_customers_version CHECK (version >= 0);

-- 2. 고객 변경 기록 -------------------------------------------------------------------------
-- 수정 요청 1건(실제로 바뀐 칸이 하나 이상일 때)마다 1행. 바뀐 칸이 없으면 행을 만들지 않는다(CB-52 결정: 같은 번호 = 변경 없음).
-- 매장 격리: 고객·처리 직원 모두 같은 매장이어야 한다 — V5 와 같은 복합 외래 키.
CREATE TABLE customer_change_logs (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id      UUID        NOT NULL,
    customer_id   UUID        NOT NULL,
    changed_by    UUID        NOT NULL,
    changed_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    name_changed  BOOLEAN     NOT NULL,
    phone_changed BOOLEAN     NOT NULL,
    memo_changed  BOOLEAN     NOT NULL,
    CONSTRAINT fk_customer_change_logs_store_customer
        FOREIGN KEY (store_id, customer_id) REFERENCES customers (store_id, id),
    CONSTRAINT fk_customer_change_logs_store_changer
        FOREIGN KEY (store_id, changed_by) REFERENCES employees (store_id, id),
    CONSTRAINT ck_customer_change_logs_any_field
        CHECK (name_changed OR phone_changed OR memo_changed)
);
-- 인덱스는 두지 않는다: 이 기록을 읽는 화면·API 요구사항이 없고(통합 테스트만 읽는다), 고객은 지우지 않아
--   외래 키 검사용 인덱스가 쓰이는 경로도 없다 — performed_by·accessed_by 와 같은 근거(2026-09-28). 조회 요구가 생기면 그때 추가.

-- 3. 변경 기록은 고치거나 지울 수 없다 (장부와 같은 방식) ------------------------------------------
CREATE FUNCTION fn_customer_change_logs_append_only() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'customer_change_logs is append-only: % is not allowed', TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER trg_customer_change_logs_no_update_delete
    BEFORE UPDATE OR DELETE ON customer_change_logs
    FOR EACH ROW EXECUTE FUNCTION fn_customer_change_logs_append_only();

-- TRUNCATE 는 행 트리거를 거치지 않으므로 문장 트리거로 따로 막는다
CREATE TRIGGER trg_customer_change_logs_no_truncate
    BEFORE TRUNCATE ON customer_change_logs
    FOR EACH STATEMENT EXECUTE FUNCTION fn_customer_change_logs_append_only();

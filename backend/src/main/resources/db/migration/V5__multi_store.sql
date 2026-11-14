-- 여러 매장 전환 (REQ-36, CB-63).
-- 가입 = 새 매장 + 그 매장의 사장. 입구는 운영자가 발급한 초대 코드다. 매장 사이 데이터가 섞이지 않게
--   쓰기는 DB 가 막고(복합 외래 키), 읽기는 앱이 '내 매장' 조건으로 막고, 둘 다 격리 테스트로 증명한다.
--
-- append-only(ledger_entries): 이 파일은 ledger_entries 에 UPDATE·DELETE·TRUNCATE 를 하지 않고 트리거도 건드리지 않는다.
--   store_id 칸은 상수 DEFAULT 로 추가한 뒤 DEFAULT 를 지운다 — PostgreSQL 11+ 는 상수 DEFAULT 칸 추가를 메타데이터로만
--   처리해 기존 행을 다시 쓰지 않는다(행 트리거가 불리지 않음).
--
-- 시작 상태 세 가지를 모두 처리한다.
--   ① 빈 DB(테스트·CI): 매장·데이터 없음 → 칸을 DEFAULT 없이 바로 추가
--   ② 매장 행 없이 직원만 있는 DB(로컬 개발 DB — 시드가 직원만 넣는다) → 매장 행을 하나 만들어 기존 행을 붙인다
--   ③ 실제 장부(운영 DB): 매장 1개 + 데이터 → 그 매장에 기존 행을 모두 붙인다
--   매장 행이 2개 이상이면(단일 매장 인덱스가 있던 동안에는 생길 수 없음) 멈춘다.

-- 1. 매장 표: 단일 행 전제를 푼다 ----------------------------------------------------------
ALTER TABLE store_profile RENAME TO stores;
ALTER TABLE stores RENAME CONSTRAINT store_profile_pkey TO stores_pkey;
ALTER TABLE stores RENAME CONSTRAINT ck_store_profile_name TO ck_stores_name;
DROP INDEX ux_store_profile_singleton;
-- 매장 이름은 중복을 허용한다 — 매장은 id 로 구분한다(화면에 보이는 번호 없음)

-- 2. 초대 코드 ---------------------------------------------------------------------------
-- 코드 원문은 저장하지 않는다. 운영자가 충분히 긴 무작위 코드(128비트 이상)를 만들어 전달하고 SHA-256 hex 만 넣는다.
--   무작위 코드라 소금(salt) 없이 해시로 바로 찾는다(비밀번호처럼 사람이 고른 값이 아니다).
-- 한도 검사는 앱이 조건부 UPDATE 한 문장으로 한다:
--   UPDATE store_invite_codes SET used_count = used_count + 1
--    WHERE code_hash = ? AND active AND used_count < max_uses RETURNING id
--   0행이면 없음·꺼짐·소진을 구분하지 않고 같은 오류. 동시 요청은 행 잠금으로 줄을 서고 CHECK 가 마지막 방어선이다.
-- label 은 운영자가 어느 지원 건에 준 코드인지 알아보는 메모다 — 이름·연락처 같은 개인정보는 넣지 않는다.
CREATE TABLE store_invite_codes (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    code_hash   VARCHAR(64)  NOT NULL,   -- 길이·형식은 CHECK 가 정확히 64자 소문자 hex 로 막는다
    label       VARCHAR(100) NOT NULL,
    max_uses    INTEGER      NOT NULL DEFAULT 10,
    used_count  INTEGER      NOT NULL DEFAULT 0,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    disabled_at TIMESTAMPTZ,
    CONSTRAINT ux_store_invite_codes_code_hash  UNIQUE (code_hash),
    CONSTRAINT ck_store_invite_codes_code_hash  CHECK (code_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_store_invite_codes_label      CHECK (btrim(label) <> ''),
    CONSTRAINT ck_store_invite_codes_max_uses   CHECK (max_uses >= 1),
    CONSTRAINT ck_store_invite_codes_used_count CHECK (used_count >= 0 AND used_count <= max_uses),
    CONSTRAINT ck_store_invite_codes_disabled   CHECK (active = (disabled_at IS NULL))
);

-- 매장마다 어느 코드로 열었는지(운영자만 본다). 이 전환 전에 열린 매장은 NULL.
-- 인덱스는 두지 않는다: 코드는 지우지 않고(사용 중지만), 코드별 매장 조회는 드문 운영자 수동 조회다.
ALTER TABLE stores ADD COLUMN invite_code_id UUID;
ALTER TABLE stores ADD CONSTRAINT fk_stores_invite_code_id
    FOREIGN KEY (invite_code_id) REFERENCES store_invite_codes (id);

-- 3. 기존 행을 어느 매장에 붙일지 정하고 store_id 칸을 추가한다 ------------------------------------
DO $$
DECLARE
    v_store_count INTEGER;
    v_has_rows    BOOLEAN;
    v_store_id    UUID;
    v_table       TEXT;
BEGIN
    SELECT count(*) INTO v_store_count FROM stores;
    IF v_store_count > 1 THEN
        RAISE EXCEPTION 'V5: stores 행이 % 개다 — 단일 매장 데이터만 옮길 수 있다', v_store_count;
    END IF;
    -- 아래 5절 '매장당 활성 사장 1명' 인덱스를 만들기 전에, 원인을 알 수 있는 메시지로 먼저 멈춘다
    IF (SELECT count(*) FROM employees WHERE role = 'ADMIN' AND active) > 1 THEN
        RAISE EXCEPTION 'V5: 활성 ADMIN 이 % 명이다 — 매장당 활성 사장은 1명이어야 한다',
            (SELECT count(*) FROM employees WHERE role = 'ADMIN' AND active);
    END IF;

    SELECT EXISTS (SELECT 1 FROM employees)
        OR EXISTS (SELECT 1 FROM customers)
        OR EXISTS (SELECT 1 FROM prepaid_accounts)
        OR EXISTS (SELECT 1 FROM ledger_entries)
      INTO v_has_rows;

    IF v_store_count = 1 THEN
        SELECT id INTO v_store_id FROM stores;                                   -- ③
    ELSIF v_has_rows THEN
        INSERT INTO stores (name) VALUES ('기존 매장') RETURNING id INTO v_store_id;  -- ②
    END IF;

    FOREACH v_table IN ARRAY ARRAY['employees', 'customers', 'prepaid_accounts', 'ledger_entries'] LOOP
        IF v_store_id IS NULL THEN                                               -- ① 빈 표에는 바로 NOT NULL 칸
            EXECUTE format('ALTER TABLE %I ADD COLUMN store_id UUID NOT NULL', v_table);
        ELSE                                                                     -- ②③ 상수 DEFAULT → 지우기
            EXECUTE format('ALTER TABLE %I ADD COLUMN store_id UUID NOT NULL DEFAULT %L::uuid', v_table, v_store_id);
            EXECUTE format('ALTER TABLE %I ALTER COLUMN store_id DROP DEFAULT', v_table);
        END IF;
    END LOOP;
END
$$;

-- 4. 매장 사이 연결을 DB 가 막는다 (복합 외래 키) ------------------------------------------------
-- 복합 외래 키가 기댈 (store_id, id) 유일 제약. id 가 이미 기본 키라 값으로는 늘 성립하고, 외래 키의 대상이 되려고 둔다.
ALTER TABLE employees        ADD CONSTRAINT ux_employees_store_id_id        UNIQUE (store_id, id);
ALTER TABLE customers        ADD CONSTRAINT ux_customers_store_id_id        UNIQUE (store_id, id);
ALTER TABLE prepaid_accounts ADD CONSTRAINT ux_prepaid_accounts_store_id_id UNIQUE (store_id, id);

-- 직원·고객은 매장에 직접 속한다
ALTER TABLE employees ADD CONSTRAINT fk_employees_store_id FOREIGN KEY (store_id) REFERENCES stores (id);
ALTER TABLE customers ADD CONSTRAINT fk_customers_store_id FOREIGN KEY (store_id) REFERENCES stores (id);

-- 계좌 → 고객: 같은 매장의 고객만. 기존 단일 칸 외래 키는 복합 외래 키가 대신한다.
-- 계좌·거래의 store_id 에 stores 직접 외래 키를 따로 두지 않는다 — 복합 외래 키가 상위 행의 store_id 와 같음을 보장하고,
--   상위 행(고객·직원)의 store_id 는 stores 를 가리킨다.
ALTER TABLE prepaid_accounts DROP CONSTRAINT prepaid_accounts_customer_id_fkey;
ALTER TABLE prepaid_accounts ADD CONSTRAINT fk_prepaid_accounts_store_customer
    FOREIGN KEY (store_id, customer_id) REFERENCES customers (store_id, id);

-- 거래 → 계좌, 거래 → 처리 직원: 같은 매장만 (제약 추가는 기존 행을 읽어 검사할 뿐 고치지 않는다)
ALTER TABLE ledger_entries DROP CONSTRAINT ledger_entries_account_id_fkey;
ALTER TABLE ledger_entries ADD CONSTRAINT fk_ledger_entries_store_account
    FOREIGN KEY (store_id, account_id) REFERENCES prepaid_accounts (store_id, id);
ALTER TABLE ledger_entries DROP CONSTRAINT ledger_entries_performed_by_fkey;
ALTER TABLE ledger_entries ADD CONSTRAINT fk_ledger_entries_store_performer
    FOREIGN KEY (store_id, performed_by) REFERENCES employees (store_id, id);
-- 반제 대상(reverses_id)은 같은 계좌여야 한다 — 같은 표 안 연결이라 지금처럼 도메인 검증 + 정합성 점검 쿼리(§6.3)로 잡는다.
--   계좌가 같으면 매장도 같다.

-- 5. 매장당 활성 사장 1명 --------------------------------------------------------------------
-- 비활성이 된 이전 사장 행은 세지 않는다 — 운영자가 사장을 바꾸면(운영 점검 가이드 10.3) 이전 사장 행이 비활성으로 남는다.
-- 유일 인덱스는 문장마다 바로 검사되므로, 사장 교체는 한 트랜잭션 안에서 '기존 사장 비활성화'를 먼저, 'ADMIN 전환'을 나중에 한다.
CREATE UNIQUE INDEX ux_employees_store_active_admin ON employees (store_id)
    WHERE role = 'ADMIN' AND active;

-- 6. '내 매장' 조회를 받치는 인덱스 --------------------------------------------------------------
-- 고객 목록: WHERE store_id = ? [AND active] ORDER BY created_at DESC, id DESC
--   활성만(기본, REQ-2·4)과 비활성 포함(REQ-33) 두 경로를 인덱스 하나로 받친다 — 정렬 칸까지 인덱스 순서와 같아
--   따로 정렬하지 않고, 활성 조건은 걸러 내기로 처리한다(고객 대부분이 활성).
DROP INDEX ix_customers_active_created;
CREATE INDEX ix_customers_store_created ON customers (store_id, created_at DESC, id DESC);
-- 연락처 중복 확인은 같은 매장 안에서만(REQ-1·36): WHERE store_id = ? AND phone = ?
DROP INDEX ix_customers_phone;
CREATE INDEX ix_customers_store_phone ON customers (store_id, phone);
-- 직원 목록·관리 조회는 ux_employees_store_id_id(store_id 가 앞)가 받친다. 거래 조회는 계좌 기준이라 기존 인덱스 그대로.
-- ledger_entries (store_id, performed_by) 외래 키에는 인덱스를 두지 않는다 — performed_by 와 같은 근거(직원은 지우지 않고
--   직원별 거래 조회 요구사항이 없다).

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

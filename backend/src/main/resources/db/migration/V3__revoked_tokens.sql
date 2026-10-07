-- 로그아웃한 토큰 목록 (REQ-15, CB-42).
-- 토큰은 서버에 저장하지 않는 JWT 라서, 로그아웃한 토큰을 만료 전까지 거절하려면 그 jti 를 기억해야 한다.
-- 인증할 때마다 직원 상태와 함께 이 표를 jti(기본 키)로 조회한다.
--
-- 원장(ledger_entries)이 아니므로 append-only 대상이 아니다. 만료(expires_at)가 지난 행은 다시 쓰일 일이 없어 정리(DELETE)해도 된다.
-- employee_id 의 FK 인덱스는 두지 않는다: 직원은 삭제하지 않고(active 플래그) 직원별 폐기 토큰 조회 요구사항도 없어서
-- 인덱스가 쓰이는 경로가 없다 (ledger_entries.performed_by 와 같은 근거).
CREATE TABLE revoked_tokens (
    jti         UUID        PRIMARY KEY,
    employee_id UUID        NOT NULL REFERENCES employees (id),
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- 만료된 행 정리용
CREATE INDEX ix_revoked_tokens_expires_at ON revoked_tokens (expires_at);

-- REQ-1: 고객 연락처는 필수다. V1 은 customers.phone 에 NULL 을 허용했으므로
-- API·도메인 검증만으로 지키던 규칙을 DB 에서도 막는다.
-- ck_customers_phone_digits 의 "phone IS NULL OR" 분기는 이후 쓰이지 않지만,
-- 제약을 지우고 다시 만드는 변경을 피하려고 그대로 둔다.
ALTER TABLE customers ALTER COLUMN phone SET NOT NULL;

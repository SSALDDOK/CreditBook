package com.creditbook.customer.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 고객 목록 한 줄 (REQ-2) — 고객과 선결제 잔액을 한 쿼리로 읽은 조회 전용 값. 엔티티가 아니므로 이것으로 상태를 바꾸지 않는다.
 *
 * @param phone 저장된 원문(숫자 정규형). 응답으로 내보낼 때는 반드시 {@link PhoneNumberMasker} 를 거친다
 * @param balance prepaid_accounts.balance (원 단위 정수)
 */
public record CustomerSummary(
		UUID id,
		String name,
		String phone,
		BigDecimal balance,
		Instant createdAt) {
}

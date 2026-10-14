package com.creditbook.customer.controller.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.creditbook.customer.domain.CustomerDetail;
import com.creditbook.prepaid.domain.LedgerEntryType;

/**
 * 고객 상세의 최근 거래 한 건 (GET /api/customers/{id} 의 recentEntries[]). 금액은 모두 서버가 저장한 값 그대로다.
 *
 * @param signedAmount 잔액에 더해진 부호 있는 금액 (USE·CHARGE_CANCEL 은 음수)
 * @param balanceAfter 이 거래 직후 잔액
 * @param performedByName 처리 직원 이름
 * @param cancelled 이 거래가 반제(취소)됐으면 true
 */
public record RecentLedgerEntryResponse(
		UUID id,
		LedgerEntryType type,
		BigDecimal amount,
		BigDecimal signedAmount,
		BigDecimal balanceAfter,
		String memo,
		Instant performedAt,
		String performedByName,
		boolean cancelled) {

	public static RecentLedgerEntryResponse from(CustomerDetail.RecentEntry entry) {
		return new RecentLedgerEntryResponse(entry.id(), entry.type(), entry.amount(), entry.signedAmount(),
				entry.balanceAfter(), entry.memo(), entry.performedAt(), entry.performedByName(), entry.cancelled());
	}

}

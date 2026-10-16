package com.creditbook.prepaid.controller.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.creditbook.prepaid.application.LedgerHistoryItem;
import com.creditbook.prepaid.domain.LedgerEntryType;
import com.creditbook.prepaid.domain.LedgerHistoryEntry;

/**
 * 거래 이력의 거래 한 건 (GET /api/customers/{customerId}/transactions 의 content[]). 금액은 모두 서버가 저장한 값 그대로다.
 * 고객 상세의 최근 거래와 같은 필드에 reversesId·cancellable 을 더했다.
 *
 * @param signedAmount 잔액에 더해진 부호 있는 금액 (USE·CHARGE_CANCEL 은 음수)
 * @param balanceAfter 이 거래 직후 잔액
 * @param memo 메모. 취소 행이면 취소 사유
 * @param performedByName 처리 직원 이름
 * @param cancelled 이 거래가 반제(취소)됐으면 true
 * @param reversesId 취소 행이면 원거래 ID, 아니면 null
 * @param cancellable 지금 취소할 수 있으면 true (서버 판단 — 사용 건은 기한 없이, 충전 건은 당일만, 취소 행은 항상 false).
 *        잔액은 보지 않는다
 */
public record LedgerEntryListItemResponse(
		UUID id,
		LedgerEntryType type,
		BigDecimal amount,
		BigDecimal signedAmount,
		BigDecimal balanceAfter,
		String memo,
		Instant performedAt,
		String performedByName,
		boolean cancelled,
		UUID reversesId,
		boolean cancellable) {

	public static LedgerEntryListItemResponse from(LedgerHistoryItem item) {
		LedgerHistoryEntry entry = item.entry();
		return new LedgerEntryListItemResponse(entry.id(), entry.type(), entry.amount(), entry.signedAmount(),
				entry.balanceAfter(), entry.memo(), entry.performedAt(), entry.performedByName(), entry.cancelled(),
				entry.reversesId(), item.cancellable());
	}

}

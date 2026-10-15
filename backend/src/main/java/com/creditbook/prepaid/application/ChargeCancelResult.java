package com.creditbook.prepaid.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.creditbook.prepaid.domain.LedgerEntry;
import com.creditbook.prepaid.domain.LedgerEntryType;

/**
 * 충전 취소 결과. 컨트롤러가 엔티티를 직접 다루지 않도록 서비스가 필요한 값만 담아 돌려준다.
 *
 * @param entryId 만들어진 CHARGE_CANCEL 거래의 ID
 * @param amount 줄어든 금액 (= 원 충전 금액, 전액 취소)
 * @param balanceAfter 취소 후 잔액
 * @param reason 저장된 취소 사유 (앞뒤 공백 제거 후 memo 컬럼 값)
 * @param reversesId 취소한 원 충전 거래의 ID
 * @param replayed 같은 요청 키로 다시 온 요청이라 새 거래 없이 먼저 만든 거래를 돌려주면 true (REQ-10)
 */
public record ChargeCancelResult(
		UUID entryId,
		UUID accountId,
		UUID customerId,
		LedgerEntryType type,
		BigDecimal amount,
		BigDecimal balanceAfter,
		String reason,
		UUID reversesId,
		UUID performedBy,
		Instant performedAt,
		boolean replayed) {

	static ChargeCancelResult of(UUID customerId, LedgerWriteOutcome outcome) {
		LedgerEntry entry = outcome.entry();
		return new ChargeCancelResult(entry.getId(), entry.getAccountId(), customerId, entry.getType(),
				entry.getAmount(), entry.getBalanceAfter(), entry.getMemo(), entry.getReversesId(),
				entry.getPerformedBy(), entry.getPerformedAt(), outcome.replayed());
	}

}

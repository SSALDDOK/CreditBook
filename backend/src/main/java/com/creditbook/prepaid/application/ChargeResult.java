package com.creditbook.prepaid.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.creditbook.prepaid.domain.LedgerEntry;
import com.creditbook.prepaid.domain.LedgerEntryType;

/**
 * 충전 결과. 컨트롤러가 엔티티를 직접 다루지 않도록 서비스가 필요한 값만 담아 돌려준다.
 *
 * @param entryId 만들어진 CHARGE 거래의 ID
 * @param balanceAfter 충전 후 잔액 (= 계좌의 현재 잔액)
 */
public record ChargeResult(
		UUID entryId,
		UUID accountId,
		UUID customerId,
		LedgerEntryType type,
		BigDecimal amount,
		BigDecimal balanceAfter,
		String memo,
		UUID performedBy,
		Instant performedAt) {

	static ChargeResult of(UUID customerId, LedgerEntry entry) {
		return new ChargeResult(entry.getId(), entry.getAccountId(), customerId, entry.getType(), entry.getAmount(),
				entry.getBalanceAfter(), entry.getMemo(), entry.getPerformedBy(), entry.getPerformedAt());
	}

}

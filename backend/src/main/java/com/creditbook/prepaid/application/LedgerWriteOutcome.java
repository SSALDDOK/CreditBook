package com.creditbook.prepaid.application;

import java.util.Objects;

import com.creditbook.prepaid.domain.LedgerEntry;

/**
 * {@link IdempotentLedgerWriter} 의 결과.
 *
 * @param entry 이번에 만든 거래, 또는 같은 요청 키로 먼저 만들어진 거래
 * @param replayed 새로 만들지 않고 먼저 만든 거래로 재응답하면 true
 */
record LedgerWriteOutcome(LedgerEntry entry, boolean replayed) {

	LedgerWriteOutcome {
		Objects.requireNonNull(entry, "entry");
	}

}

package com.creditbook.prepaid.application;

import java.util.Objects;

import com.creditbook.prepaid.domain.LedgerHistoryEntry;

/**
 * 거래 이력 한 건과 지금 취소할 수 있는지 (거래 이력 조회의 결과 항목).
 *
 * @param entry 저장된 거래와 처리 직원 이름·취소 여부
 * @param cancellable 지금(서버 시각) 취소할 수 있으면 true — {@link LedgerHistoryEntry#canBeCancelledAt} 의 판단 결과
 */
public record LedgerHistoryItem(LedgerHistoryEntry entry, boolean cancellable) {

	public LedgerHistoryItem {
		Objects.requireNonNull(entry, "entry");
	}

}

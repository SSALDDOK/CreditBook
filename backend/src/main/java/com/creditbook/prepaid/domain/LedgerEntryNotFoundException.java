package com.creditbook.prepaid.domain;

import java.util.UUID;

/**
 * 거래 ID 로 거래를 찾을 수 없을 때. 오류 코드 LEDGER_ENTRY_NOT_FOUND 와 짝이다.
 */
public class LedgerEntryNotFoundException extends PrepaidDomainException {

	private final UUID entryId;

	public LedgerEntryNotFoundException(UUID entryId) {
		super("거래를 찾을 수 없습니다.");
		this.entryId = entryId;
	}

	public UUID getEntryId() {
		return entryId;
	}

}

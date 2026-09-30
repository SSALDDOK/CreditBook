package com.creditbook.prepaid.domain;

import java.util.UUID;

/**
 * 이미 취소된 거래를 다시 취소하려 할 때. 원본 1건당 반제는 1건뿐이다
 * (DB 의 ux_ledger_entries_reverses 가 최후 방어선).
 */
public class AlreadyCancelledException extends PrepaidDomainException {

	private final UUID targetId;
	private final UUID cancelEntryId;

	AlreadyCancelledException(UUID targetId, UUID cancelEntryId) {
		super("이미 취소된 거래입니다.");
		this.targetId = targetId;
		this.cancelEntryId = cancelEntryId;
	}

	public UUID getTargetId() {
		return targetId;
	}

	/** 먼저 만들어진 취소 거래의 ID. */
	public UUID getCancelEntryId() {
		return cancelEntryId;
	}

}

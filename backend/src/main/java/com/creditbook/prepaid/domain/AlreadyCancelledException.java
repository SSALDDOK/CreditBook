package com.creditbook.prepaid.domain;

import java.util.UUID;

/**
 * 이미 취소된 거래를 다시 취소하려 할 때. 원본 1건당 반제는 1건뿐이다
 * (DB 의 ux_ledger_entries_reverses 가 최후 방어선). 오류 코드 ALREADY_CANCELLED 와 짝이다.
 */
public class AlreadyCancelledException extends PrepaidDomainException {

	private final UUID targetId;
	private final UUID cancelEntryId;

	AlreadyCancelledException(UUID targetId, LedgerEntryType targetType, UUID cancelEntryId) {
		super(messageFor(targetType));
		this.targetId = targetId;
		this.cancelEntryId = cancelEntryId;
	}

	/** 화면에 그대로 보여 줄 문구. 대상 유형에 맞춰 말한다. */
	private static String messageFor(LedgerEntryType targetType) {
		return targetType == LedgerEntryType.USE ? "이미 취소된 사용 건입니다." : "이미 취소된 거래입니다.";
	}

	public UUID getTargetId() {
		return targetId;
	}

	/** 먼저 만들어진 취소 거래의 ID. */
	public UUID getCancelEntryId() {
		return cancelEntryId;
	}

}

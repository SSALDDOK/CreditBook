package com.creditbook.prepaid.domain;

/**
 * 거래 유형. DB 의 ck_ledger_entries_type 과 같은 네 값이며, 항상 이름(STRING)으로 저장한다.
 */
public enum LedgerEntryType {

	CHARGE(false, null),
	USE(true, null),
	CHARGE_CANCEL(true, CHARGE),
	USE_CANCEL(false, USE);

	private final boolean debit;
	private final LedgerEntryType reversedType;

	LedgerEntryType(boolean debit, LedgerEntryType reversedType) {
		this.debit = debit;
		this.reversedType = reversedType;
	}

	/** 잔액을 줄이는 유형인가. ledger_entries.signed_amount 생성식(USE, CHARGE_CANCEL 이면 음수)과 같은 규칙이다. */
	public boolean isDebit() {
		return debit;
	}

	/** 다른 거래를 되돌리는 반제 유형인가 (reverses_id 필수). */
	public boolean isReversal() {
		return reversedType != null;
	}

	/** 이 반제 유형이 되돌릴 수 있는 원본 유형. 반제 유형이 아니면 null. */
	public LedgerEntryType reversedType() {
		return reversedType;
	}

}

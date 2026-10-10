package com.creditbook.prepaid.domain;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * 같은 요청 키로 다시 온 요청이 "같은 요청" 인지 비교할 기준 (REQ-10). 비교는 {@link LedgerEntry#isSameRequest} 가 한다.
 * <p>
 * 원 거래(CHARGE·USE)는 유형·금액·메모를, 반제 거래(CHARGE_CANCEL·USE_CANCEL)는 유형·대상 거래·사유를 비교한다.
 * 반제 금액은 요청이 정하지 않고 대상 거래가 정하므로 비교하지 않는다. 고객(계좌)은 비교할 때 따로 넘긴다.
 * 메모·사유는 저장할 때와 같은 규칙(앞뒤 공백 제거, 빈 값은 없음)으로 맞춘 뒤 비교한다.
 */
public final class IdempotentRequest {

	private final LedgerEntryType type;
	private final BigDecimal amount;
	private final String memo;
	private final UUID reversesId;

	private IdempotentRequest(LedgerEntryType type, BigDecimal amount, String memo, UUID reversesId) {
		this.type = Objects.requireNonNull(type, "type");
		this.amount = amount;
		this.memo = LedgerEntry.normalizeMemo(memo);
		this.reversesId = reversesId;
	}

	/**
	 * 원 거래(CHARGE·USE) 요청. 금액이 null 이어도 만든다 — 금액 규칙은 도메인 연산이 판단하고,
	 * 비교에서는 null 금액이 저장된 어떤 거래와도 같지 않다.
	 */
	public static IdempotentRequest original(LedgerEntryType type, BigDecimal amount, String memo) {
		if (type.isReversal()) {
			throw new IllegalArgumentException("원 거래 유형이어야 한다: " + type);
		}
		return new IdempotentRequest(type, amount, memo, null);
	}

	/** 반제 거래(CHARGE_CANCEL·USE_CANCEL) 요청. */
	public static IdempotentRequest reversal(LedgerEntryType type, UUID reversesId, String reason) {
		if (!type.isReversal()) {
			throw new IllegalArgumentException("반제 유형이어야 한다: " + type);
		}
		return new IdempotentRequest(type, null, reason, Objects.requireNonNull(reversesId, "reversesId"));
	}

	LedgerEntryType type() {
		return type;
	}

	/** 원 거래 요청의 금액. 반제 요청이면 null 이고 비교하지 않는다. */
	BigDecimal amount() {
		return amount;
	}

	String memo() {
		return memo;
	}

	UUID reversesId() {
		return reversesId;
	}

}

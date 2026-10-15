package com.creditbook.prepaid.domain;

import java.time.LocalDate;
import java.util.UUID;

/**
 * 당일(영업 시간대 달력 날짜)에 등록되지 않은 충전 건을 취소하려 할 때 (REQ-7). 거래는 만들어지지 않는다.
 * 오류 코드 CHARGE_CANCEL_NOT_SAME_DAY 와 짝이다.
 */
public class ChargeCancelNotSameDayException extends PrepaidDomainException {

	private final UUID targetId;
	private final LocalDate chargedOn;
	private final LocalDate today;

	ChargeCancelNotSameDayException(UUID targetId, LocalDate chargedOn, LocalDate today) {
		super("당일 충전 건만 취소할 수 있습니다.");
		this.targetId = targetId;
		this.chargedOn = chargedOn;
		this.today = today;
	}

	/** 취소하려던 충전 거래의 ID. */
	public UUID getTargetId() {
		return targetId;
	}

	/** 충전이 등록된 날짜 (영업 시간대 기준). */
	public LocalDate getChargedOn() {
		return chargedOn;
	}

	/** 취소를 요청한 날짜 (영업 시간대 기준). */
	public LocalDate getToday() {
		return today;
	}

}

package com.creditbook.prepaid.controller.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.creditbook.prepaid.application.ChargeResult;

/**
 * 충전 응답 — 만들어진 CHARGE 거래 한 건. 계좌 ID 와 처리 직원 ID 는 내보내지 않는다.
 *
 * @param id 거래 ID
 * @param type 거래 유형 ({@code CHARGE})
 * @param amount 충전 금액 (원 단위 정수)
 * @param balanceAfter 충전 후 잔액
 * @param performedAt 서버가 기록한 처리 시각
 */
public record ChargeResponse(
		UUID id,
		UUID customerId,
		String type,
		BigDecimal amount,
		BigDecimal balanceAfter,
		String memo,
		Instant performedAt) {

	public static ChargeResponse from(ChargeResult result) {
		return new ChargeResponse(result.entryId(), result.customerId(), result.type().name(), result.amount(),
				result.balanceAfter(), result.memo(), result.performedAt());
	}

}

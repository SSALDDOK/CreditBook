package com.creditbook.prepaid.controller.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.creditbook.prepaid.application.ChargeCancelResult;

/**
 * 충전 취소 응답 — 만들어진 CHARGE_CANCEL 반제 거래 한 건. 계좌 ID 와 처리 직원 ID 는 내보내지 않는다.
 *
 * @param id 반제 거래 ID
 * @param type 거래 유형 ({@code CHARGE_CANCEL})
 * @param amount 줄어든 금액 (원 단위 양수 — 원 충전 금액과 같다)
 * @param balanceAfter 취소 후 잔액
 * @param reason 저장된 취소 사유
 * @param reversesId 취소한 원 충전 거래 ID
 * @param performedAt 서버가 기록한 처리 시각
 */
public record ChargeCancelResponse(
		UUID id,
		UUID customerId,
		String type,
		BigDecimal amount,
		BigDecimal balanceAfter,
		String reason,
		UUID reversesId,
		Instant performedAt) {

	public static ChargeCancelResponse from(ChargeCancelResult result) {
		return new ChargeCancelResponse(result.entryId(), result.customerId(), result.type().name(), result.amount(),
				result.balanceAfter(), result.reason(), result.reversesId(), result.performedAt());
	}

}

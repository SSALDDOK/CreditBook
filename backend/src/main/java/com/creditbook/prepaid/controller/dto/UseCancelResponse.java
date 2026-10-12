package com.creditbook.prepaid.controller.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.creditbook.prepaid.application.UseCancelResult;

/**
 * 사용 취소 응답 — 만들어진 USE_CANCEL 반제 거래 한 건. 계좌 ID 와 처리 직원 ID 는 내보내지 않는다.
 *
 * @param id 반제 거래 ID
 * @param type 거래 유형 ({@code USE_CANCEL})
 * @param amount 되돌린 금액 (원 단위 양수 — 원 사용 금액과 같다)
 * @param balanceAfter 취소 후 잔액
 * @param reason 저장된 취소 사유
 * @param reversesId 취소한 원 사용 거래 ID
 * @param performedAt 서버가 기록한 처리 시각
 */
public record UseCancelResponse(
		UUID id,
		UUID customerId,
		String type,
		BigDecimal amount,
		BigDecimal balanceAfter,
		String reason,
		UUID reversesId,
		Instant performedAt) {

	public static UseCancelResponse from(UseCancelResult result) {
		return new UseCancelResponse(result.entryId(), result.customerId(), result.type().name(), result.amount(),
				result.balanceAfter(), result.reason(), result.reversesId(), result.performedAt());
	}

}

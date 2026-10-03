package com.creditbook.prepaid.controller.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.creditbook.prepaid.application.UsageResult;

/**
 * 사용 응답 — 만들어진 USE 거래 한 건. 충전 응답({@link ChargeResponse})과 같은 모양이다.
 * 계좌 ID 와 처리 직원 ID 는 내보내지 않는다.
 *
 * @param id 거래 ID
 * @param type 거래 유형 ({@code USE})
 * @param amount 사용 금액 (원 단위 양수 — 잔액에서 빠진 금액)
 * @param balanceAfter 사용 후 잔액
 * @param performedAt 서버가 기록한 처리 시각
 */
public record UsageResponse(
		UUID id,
		UUID customerId,
		String type,
		BigDecimal amount,
		BigDecimal balanceAfter,
		String memo,
		Instant performedAt) {

	public static UsageResponse from(UsageResult result) {
		return new UsageResponse(result.entryId(), result.customerId(), result.type().name(), result.amount(),
				result.balanceAfter(), result.memo(), result.performedAt());
	}

}

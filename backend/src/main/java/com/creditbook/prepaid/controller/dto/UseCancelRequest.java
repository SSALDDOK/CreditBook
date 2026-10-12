package com.creditbook.prepaid.controller.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 사용 취소 요청 (POST /api/customers/{customerId}/uses/{useEntryId}/cancel).
 * <p>
 * 여기서는 사유가 있는지만 본다. 취소 대상 규칙(같은 고객·USE 거래·이미 취소 여부)은 도메인이 판단한다.
 * 금액은 받지 않는다 — 전액 취소만 있으므로 원 사용 금액이 그대로 되돌아간다.
 *
 * @param reason 취소 사유 (필수, 거래의 memo 에 저장된다)
 */
public record UseCancelRequest(
		@NotBlank(message = "취소 사유를 입력해 주세요.")
		String reason) {
}

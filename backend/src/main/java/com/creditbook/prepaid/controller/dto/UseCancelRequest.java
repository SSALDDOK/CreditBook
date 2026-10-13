package com.creditbook.prepaid.controller.dto;

import com.creditbook.prepaid.domain.LedgerEntry;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 사용 취소 요청 (POST /api/customers/{customerId}/uses/{useEntryId}/cancel).
 * <p>
 * 여기서는 사유가 있는지와 길이만 본다. 취소 대상 규칙(같은 고객·USE 거래·이미 취소 여부)은 도메인이 판단한다.
 * 사유는 거래의 memo 컬럼에 저장되므로 충전·사용 메모와 같은 길이 제한을 둔다 — 넘으면 도메인까지 가지 않고 400 으로 거절한다.
 * 금액은 받지 않는다 — 전액 취소만 있으므로 원 사용 금액이 그대로 되돌아간다.
 *
 * @param reason 취소 사유 (필수, 거래의 memo 에 저장된다)
 */
public record UseCancelRequest(
		@NotBlank(message = "취소 사유를 입력해 주세요.")
		@Size(max = LedgerEntry.MEMO_MAX_LENGTH, message = "취소 사유는 {max}자 이하여야 합니다.")
		String reason) {
}

package com.creditbook.prepaid.controller.dto;

import java.math.BigDecimal;

import com.creditbook.prepaid.domain.LedgerEntry;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 충전 요청 (POST /api/customers/{customerId}/charges).
 * <p>
 * 여기서는 형식만 본다 — 금액이 있는지, 메모 길이. 금액의 값 규칙(1원 이상, 원 단위 정수, 1회 한도)은 도메인이 판단하고
 * INVALID_AMOUNT·CHARGE_LIMIT_EXCEEDED 로 사유를 돌려준다. 한도는 설정값이라 어노테이션에 둘 수 없다.
 * 메모는 자유 입력이다 (프리셋은 화면 입력 보조일 뿐 서버가 강제하지 않는다).
 *
 * @param amount 충전 금액 (원, JSON 숫자)
 * @param memo 메모 (선택)
 */
public record ChargeRequest(
		@NotNull(message = "금액을 입력해 주세요.")
		BigDecimal amount,

		@Size(max = LedgerEntry.MEMO_MAX_LENGTH, message = "메모는 {max}자 이하여야 합니다.")
		String memo) {
}

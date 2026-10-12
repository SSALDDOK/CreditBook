package com.creditbook.customer.controller.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.creditbook.customer.domain.CustomerSummary;
import com.creditbook.customer.domain.PhoneNumberMasker;

/**
 * 고객 목록의 한 줄 (GET /api/customers). 등록 응답({@link CustomerResponse})과 같은 필드에 활성 여부를 더한다.
 * <p>
 * 연락처 원문은 나가지 않는다 (REQ-26). 원문을 받는 생성자 대신 팩토리 메서드만 쓰고, 팩토리가 {@link PhoneNumberMasker} 로 가린 값을 넣는다.
 *
 * @param maskedPhone 가운데 자리를 가린 연락처 (예: {@code 010-****-5678})
 * @param balance 선결제 잔액 (원 단위 정수)
 * @param active 활성 고객이면 true (REQ-33). 비활성 포함 목록(includeInactive=true)에서 비활성 고객을 구분하며,
 *        기본 목록에서도 항상 담는다
 */
public record CustomerListItemResponse(
		UUID id,
		String name,
		String maskedPhone,
		BigDecimal balance,
		Instant createdAt,
		boolean active) {

	public static CustomerListItemResponse from(CustomerSummary summary) {
		return new CustomerListItemResponse(summary.id(), summary.name(), PhoneNumberMasker.mask(summary.phone()),
				summary.balance(), summary.createdAt(), summary.active());
	}

}

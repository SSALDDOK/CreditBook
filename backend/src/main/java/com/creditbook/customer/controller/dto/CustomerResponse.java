package com.creditbook.customer.controller.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.creditbook.customer.application.RegisteredCustomer;
import com.creditbook.customer.domain.CustomerSummary;
import com.creditbook.customer.domain.PhoneNumberMasker;

/**
 * 고객 응답 — 등록 응답과 목록의 한 줄에 함께 쓴다. 엔티티를 그대로 내보내지 않고 필요한 값만 담는다.
 * <p>
 * 연락처 원문은 어떤 응답에도 나가지 않는다 (REQ-26). 원문을 받는 생성자 대신 팩토리 메서드만 쓰고,
 * 팩토리가 {@link PhoneNumberMasker} 로 가린 값을 넣는다. 필드 이름도 {@code maskedPhone} 으로 두어 원문이 아님을 드러낸다.
 *
 * @param maskedPhone 가운데 자리를 가린 연락처 (예: {@code 010-****-5678})
 * @param balance 선결제 잔액 (원 단위 정수)
 */
public record CustomerResponse(
		UUID id,
		String name,
		String maskedPhone,
		BigDecimal balance,
		Instant createdAt) {

	public static CustomerResponse from(RegisteredCustomer registered) {
		return new CustomerResponse(registered.customerId(), registered.name(),
				PhoneNumberMasker.mask(registered.phone()), registered.balance(), registered.createdAt());
	}

	public static CustomerResponse from(CustomerSummary summary) {
		return new CustomerResponse(summary.id(), summary.name(), PhoneNumberMasker.mask(summary.phone()),
				summary.balance(), summary.createdAt());
	}

}

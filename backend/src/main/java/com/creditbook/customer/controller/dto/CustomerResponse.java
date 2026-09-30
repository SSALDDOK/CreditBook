package com.creditbook.customer.controller.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.creditbook.customer.application.RegisteredCustomer;

/**
 * 고객 응답. 엔티티를 그대로 내보내지 않고 필요한 값만 담는다.
 *
 * @param phone 숫자만 남긴 정규형
 * @param balance 선결제 잔액 (원 단위 정수)
 */
public record CustomerResponse(
		UUID id,
		String name,
		String phone,
		BigDecimal balance,
		Instant createdAt) {

	public static CustomerResponse from(RegisteredCustomer registered) {
		return new CustomerResponse(registered.customerId(), registered.name(), registered.phone(),
				registered.balance(), registered.createdAt());
	}

}

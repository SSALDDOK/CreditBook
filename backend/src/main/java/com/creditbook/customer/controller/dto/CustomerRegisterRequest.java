package com.creditbook.customer.controller.dto;

import com.creditbook.customer.domain.Customer;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 고객 등록 요청 (POST /api/customers).
 * <p>
 * 연락처는 입력 편의를 위해 하이픈을 허용한다 ({@code 010-1234-5678}). 하이픈은 숫자 사이에 하나씩만 올 수 있고,
 * 숫자는 9–11자리여야 한다. 저장 전에 {@link #phoneDigits()} 로 숫자만 남긴다 — 도메인과 DB 는 숫자 정규형만 받는다.
 * 연락처는 선택이며 빈 문자열은 입력하지 않은 것으로 본다.
 */
public record CustomerRegisterRequest(
		@NotBlank(message = "이름을 입력해 주세요.")
		@Size(max = Customer.NAME_MAX_LENGTH, message = "이름은 {max}자 이하여야 합니다.")
		String name,

		@Pattern(regexp = PHONE_INPUT_PATTERN, message = "연락처는 숫자 9–11자리여야 합니다. 하이픈(-)은 숫자 사이에만 쓸 수 있습니다.")
		String phone) {

	/** 빈 문자열, 또는 숫자 9–11자리(숫자 사이에 하이픈 하나씩 허용). */
	static final String PHONE_INPUT_PATTERN = "^$|^(?:[0-9]-?){8,10}[0-9]$";

	/** 저장용 연락처 — 하이픈을 지운 숫자만. 입력하지 않았으면 null. */
	public String phoneDigits() {
		if (phone == null || phone.isEmpty()) {
			return null;
		}
		return phone.replace("-", "");
	}

}

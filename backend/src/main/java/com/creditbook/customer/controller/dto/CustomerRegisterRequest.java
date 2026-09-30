package com.creditbook.customer.controller.dto;

import com.creditbook.customer.domain.Customer;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 고객 등록 요청 (POST /api/customers).
 * <p>
 * 이름과 연락처는 모두 필수다 (연락처 필수: 2026-09-30 사용자 결정). null·빈 문자열·필드 누락은 400.
 * 연락처는 입력 편의를 위해 하이픈을 허용한다 ({@code 010-1234-5678}). 하이픈은 숫자 사이에 하나씩만 올 수 있고,
 * 숫자는 9–11자리여야 한다. 저장 전에 {@link #phoneDigits()} 로 숫자만 남긴다 — 도메인과 DB 는 숫자 정규형만 받는다.
 */
public record CustomerRegisterRequest(
		@NotBlank(message = "이름을 입력해 주세요.")
		@Size(max = Customer.NAME_MAX_LENGTH, message = "이름은 {max}자 이하여야 합니다.")
		String name,

		@NotBlank(message = "연락처를 입력해 주세요.")
		@Pattern(regexp = PHONE_INPUT_PATTERN, message = "연락처는 숫자 9–11자리여야 합니다. 하이픈(-)은 숫자 사이에만 쓸 수 있습니다.")
		String phone) {

	/**
	 * 숫자 9–11자리(숫자 사이에 하이픈 하나씩 허용).
	 * 빈 문자열은 여기서 통과시키고 @NotBlank 가 "입력해 주세요" 하나로만 거절하게 한다 (같은 필드에 오류 두 개가 나가지 않도록).
	 */
	static final String PHONE_INPUT_PATTERN = "^$|^(?:[0-9]-?){8,10}[0-9]$";

	/** 저장용 연락처 — 하이픈을 지운 숫자만. 필수 검증은 @NotBlank 와 도메인이 한다. */
	public String phoneDigits() {
		return phone == null ? null : phone.replace("-", "");
	}

}

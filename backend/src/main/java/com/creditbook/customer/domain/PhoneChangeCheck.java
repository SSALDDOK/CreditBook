package com.creditbook.customer.domain;

/**
 * 고객 정보 수정(REQ-3)에서 연락처가 실제로 바뀔 때만 불리는 확인 — 같은 매장의 같은 번호 고객 확인을 서비스가 넣는다.
 * {@link Customer#edit} 가 값 검증 뒤, 상태를 바꾸기 전에 부른다. 거절하려면 예외를 던진다 (고객은 그대로 남는다).
 */
@FunctionalInterface
public interface PhoneChangeCheck {

	/** 아무것도 확인하지 않음. */
	PhoneChangeCheck NONE = newPhone -> {
	};

	/**
	 * @param newPhone 바뀔 연락처 (숫자 정규형)
	 * @throws DuplicatePhoneException 같은 번호 고객이 있어 수정을 보류할 때
	 */
	void check(String newPhone);

}

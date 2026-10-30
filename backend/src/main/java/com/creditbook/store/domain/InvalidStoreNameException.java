package com.creditbook.store.domain;

/**
 * 매장명으로 쓸 수 없는 값 — null, 공백뿐, 앞뒤 공백을 빼고 30자 초과 (REQ-29). DB 의 ck_store_profile_name 보다 먼저 막는다.
 * 요청 DTO 검증이 먼저 막으므로 여기까지 오는 것은 방어선이다.
 */
public class InvalidStoreNameException extends StoreDomainException {

	public InvalidStoreNameException(String message) {
		super(message);
	}

}

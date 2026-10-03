package com.creditbook.global.security;

/**
 * 현재 직원을 알 수 없는 요청. 처리 직원 없이 거래를 만들 수 없으므로 거절한다 (401).
 */
public class UnauthenticatedException extends RuntimeException {

	public UnauthenticatedException() {
		super("로그인이 필요합니다.");
	}

}

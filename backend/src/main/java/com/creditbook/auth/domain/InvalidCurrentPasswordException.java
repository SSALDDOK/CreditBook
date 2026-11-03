package com.creditbook.auth.domain;

/**
 * 비밀번호 변경 때 현재 비밀번호가 틀림 (REQ-30). 로그인 실패와 같은 연속 실패 횟수로 센다 (REQ-15) — 기준 횟수째 틀리면 이 예외 대신
 * {@link AccountLockedException} 이 된다. 오류 코드 INVALID_CURRENT_PASSWORD 와 짝이다.
 */
public class InvalidCurrentPasswordException extends RuntimeException {

	public InvalidCurrentPasswordException() {
		super("현재 비밀번호가 올바르지 않습니다.");
	}

}

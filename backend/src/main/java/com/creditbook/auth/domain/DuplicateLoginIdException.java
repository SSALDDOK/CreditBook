package com.creditbook.auth.domain;

/**
 * 이미 쓰이는 로그인 아이디로 직원 추가 (REQ-30). 비활성 직원의 아이디도 쓰이는 것으로 본다 — 아이디는 거래 처리자를 가리키는 데 다시 쓰지 않는다.
 * 대소문자를 구분해 정확히 같을 때만 중복이다 (ux_employees_login_id 와 같은 기준). 오류 코드 DUPLICATE_LOGIN_ID 와 짝이다.
 */
public class DuplicateLoginIdException extends RuntimeException {

	public DuplicateLoginIdException() {
		super("이미 사용 중인 아이디입니다.");
	}

	public DuplicateLoginIdException(Throwable cause) {
		this();
		initCause(cause);
	}

}

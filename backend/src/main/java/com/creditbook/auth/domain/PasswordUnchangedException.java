package com.creditbook.auth.domain;

/**
 * 새 비밀번호가 현재 비밀번호와 같음 (REQ-30). 초기 비밀번호를 그대로 두면 사장이 아는 비밀번호로 계속 로그인할 수 있기 때문이다.
 * 오류 코드 PASSWORD_UNCHANGED 와 짝이다.
 */
public class PasswordUnchangedException extends RuntimeException {

	public PasswordUnchangedException() {
		super("현재 비밀번호와 다른 비밀번호를 입력해 주세요.");
	}

}

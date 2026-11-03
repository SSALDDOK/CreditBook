package com.creditbook.auth.domain;

/**
 * 사장(ADMIN) 계정이나 본인 계정을 비활성화·비밀번호 초기화하려 함 (REQ-30). 사장 교체·분실 복구는 앱이 아니라 운영자 절차로 한다.
 * 오류 코드 ADMIN_ACCOUNT_PROTECTED 와 짝이다.
 */
public class AdminAccountProtectedException extends RuntimeException {

	public AdminAccountProtectedException() {
		super("사장님 계정은 비활성화하거나 비밀번호를 초기화할 수 없습니다.");
	}

}

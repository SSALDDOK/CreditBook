package com.creditbook.global.validation;

/**
 * 비밀번호와 비밀번호 확인을 함께 받는 요청. {@link PasswordConfirmed} 를 붙이는 대상이 구현한다.
 */
public interface PasswordConfirmation {

	String password();

	String passwordConfirm();

}

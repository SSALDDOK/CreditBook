package com.creditbook.auth.domain;

import java.nio.charset.StandardCharsets;

/**
 * 직원 계정을 새로 만들 때의 아이디·비밀번호·이름 규칙 (REQ-29). 매장 개설과 직원 추가가 같은 규칙을 쓴다.
 * <p>
 * 요청 DTO 의 검증 어노테이션이 이 상수를 참조한다 — 규칙을 바꿀 때 이 파일 한 곳만 고친다.
 * 이미 있는 계정의 로그인에는 적용하지 않는다 (로그인은 사유를 구분하지 않고 401 로 거절한다).
 */
public final class EmployeeCredentialRules {

	/** 로그인 아이디: 영문·숫자·마침표 4–20자. */
	public static final String LOGIN_ID_PATTERN = "^[A-Za-z0-9.]{4,20}$";

	public static final int PASSWORD_MIN_LENGTH = 8;

	public static final int PASSWORD_MAX_LENGTH = 72;

	/** BCrypt 는 72바이트까지만 쓰고(뒤는 버림) 넘으면 해시를 만들지 않는다. 한글은 글자당 3바이트라 글자 수와 따로 본다. */
	public static final int PASSWORD_MAX_BYTES = 72;

	/** employees.name VARCHAR(50). 앞뒤 공백을 뺀 글자 수. */
	public static final int NAME_MAX_LENGTH = 50;

	private EmployeeCredentialRules() {
	}

	public static boolean isValidLoginId(String loginId) {
		return loginId != null && loginId.matches(LOGIN_ID_PATTERN);
	}

	/** 비밀번호를 UTF-8 로 바꿨을 때 BCrypt 가 받는 길이(72바이트) 안인가. */
	public static boolean isWithinPasswordBytes(String password) {
		return password != null && password.getBytes(StandardCharsets.UTF_8).length <= PASSWORD_MAX_BYTES;
	}

}

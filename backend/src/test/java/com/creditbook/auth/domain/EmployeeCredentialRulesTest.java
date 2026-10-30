package com.creditbook.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 새 직원 계정의 아이디·비밀번호 규칙 (REQ-29 경계 조건: 아이디 영문·숫자·마침표 4–20자, 비밀번호 8–72자이고 BCrypt 72바이트 이하).
 */
@Tag("REQ-29")
class EmployeeCredentialRulesTest {

	@ParameterizedTest(name = "[{index}] \"{0}\"")
	@ValueSource(strings = { "kim1", "kim.owner", "A1.b2", "abcdefghij0123456789" })
	@DisplayName("아이디는 영문·숫자·마침표 4–20자이면 쓸 수 있다")
	void valid_login_ids(String loginId) {
		assertThat(EmployeeCredentialRules.isValidLoginId(loginId)).isTrue();
	}

	@ParameterizedTest(name = "[{index}] \"{0}\"")
	@NullAndEmptySource
	@ValueSource(strings = { "kim", "abcdefghij0123456789x", "kim owner", "kim-owner", "kim_owner", "김사장님", "kim@a.b",
			"kim.owner\n" })
	@DisplayName("아이디가 4자 미만·20자 초과이거나 영문·숫자·마침표 밖의 글자가 있으면 쓸 수 없다")
	void invalid_login_ids(String loginId) {
		assertThat(EmployeeCredentialRules.isValidLoginId(loginId)).isFalse();
	}

	@ParameterizedTest(name = "[{index}] {0}바이트")
	@ValueSource(ints = { 1, 72 })
	@DisplayName("비밀번호가 UTF-8 72바이트 이하이면 BCrypt 가 받는다")
	void password_within_72_bytes(int bytes) {
		assertThat(EmployeeCredentialRules.isWithinPasswordBytes("a".repeat(bytes))).isTrue();
	}

	@ParameterizedTest(name = "[{index}] \"{0}\"")
	@ValueSource(strings = { "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
			"가나다라마바사아자차카타파하가나다라마바사아자차카" })
	@DisplayName("비밀번호가 UTF-8 73바이트 이상이면 BCrypt 가 받지 않는다 — 한글 25자(75바이트)도 해당한다")
	void password_over_72_bytes(String password) {
		assertThat(EmployeeCredentialRules.isWithinPasswordBytes(password)).isFalse();
	}

	@Test
	@DisplayName("비밀번호가 없으면 바이트 검사를 통과하지 않는다")
	void null_password_is_not_within_bytes() {
		assertThat(EmployeeCredentialRules.isWithinPasswordBytes(null)).isFalse();
	}

}

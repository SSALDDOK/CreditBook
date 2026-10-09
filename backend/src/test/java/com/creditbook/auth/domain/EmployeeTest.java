package com.creditbook.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("REQ-15")
@Tag("REQ-22")
class EmployeeTest {

	private static final Instant CREATED_AT = Instant.parse("2026-10-01T00:00:00Z");
	private static final String HASH = "$2a$10$abcdefghijklmnopqrstuuJ8m0zW8V0u1Yy3s3b4c5d6e7f8g9h0i";

	@Test
	@DisplayName("새 직원은 활성 상태이고 처음 받은 비밀번호를 바꿔야 한다")
	void new_employee_is_active_and_must_change_password() {
		// when
		Employee employee = Employee.create("owner", HASH, "김사장", Role.ADMIN, CREATED_AT);

		// then
		assertThat(employee.getId()).isNotNull();
		assertThat(employee.getLoginId()).isEqualTo("owner");
		assertThat(employee.getPasswordHash()).isEqualTo(HASH);
		assertThat(employee.getName()).isEqualTo("김사장");
		assertThat(employee.getRole()).isEqualTo(Role.ADMIN);
		assertThat(employee.isActive()).isTrue();
		assertThat(employee.canLogin()).isTrue();
		assertThat(employee.mustChangePassword()).isTrue();
		assertThat(employee.getLastLoginAt()).isNull();
		assertThat(employee.getCreatedAt()).isEqualTo(CREATED_AT);
		assertThat(employee.getUpdatedAt()).isEqualTo(CREATED_AT);
	}

	@Test
	@DisplayName("로그인에 성공하면 마지막 로그인 시각만 바뀌고 프로필 변경 시각은 그대로다")
	void record_login_changes_only_last_login_at() {
		// given
		Employee employee = Employee.create("staff1", HASH, "이직원", Role.STAFF, CREATED_AT);
		Instant loggedInAt = Instant.parse("2026-10-07T09:00:00Z");

		// when
		employee.recordLogin(loggedInAt);

		// then
		assertThat(employee.getLastLoginAt()).isEqualTo(loggedInAt);
		assertThat(employee.getUpdatedAt()).isEqualTo(CREATED_AT);
	}

	@Test
	@DisplayName("직원의 문자열 표현에는 비밀번호 해시와 로그인 아이디가 없다")
	void to_string_hides_password_hash() {
		// when
		String text = Employee.create("owner", HASH, "김사장", Role.ADMIN, CREATED_AT).toString();

		// then
		assertThat(text).doesNotContain(HASH).doesNotContain("owner").contains("ADMIN");
	}

	@ParameterizedTest(name = "[{index}] \"{0}\"")
	@NullAndEmptySource
	@ValueSource(strings = { "  " })
	@DisplayName("아이디·비밀번호 해시·이름이 비어 있으면 직원을 만들 수 없다")
	void blank_fields_are_rejected(String blank) {
		assertThatThrownBy(() -> Employee.create(blank, HASH, "김사장", Role.ADMIN, CREATED_AT))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> Employee.create("owner", blank, "김사장", Role.ADMIN, CREATED_AT))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> Employee.create("owner", HASH, blank, Role.ADMIN, CREATED_AT))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("역할·생성 시각이 없으면 직원을 만들 수 없다")
	void null_role_or_time_is_rejected() {
		assertThatThrownBy(() -> Employee.create("owner", HASH, "김사장", null, CREATED_AT))
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> Employee.create("owner", HASH, "김사장", Role.ADMIN, null))
				.isInstanceOf(NullPointerException.class);
	}

	@ParameterizedTest(name = "[{index}] {0} → {1}")
	@CsvSource({ "ADMIN, ROLE_ADMIN", "STAFF, ROLE_STAFF" })
	@DisplayName("역할은 ROLE_ 접두사가 붙은 권한 이름으로 바뀐다")
	void role_authority(Role role, String authority) {
		assertThat(role.authority()).isEqualTo(authority);
	}

	@ParameterizedTest(name = "[{index}] active={0}, revoked={1} → {2}")
	@CsvSource({ "true, false, true", "false, false, false", "true, true, false", "false, true, false" })
	@DisplayName("활성 직원의 폐기되지 않은 토큰만 인증할 수 있다")
	void auth_status_can_authenticate(boolean active, boolean revoked, boolean expected) {
		assertThat(new EmployeeAuthStatus(active, Role.STAFF, revoked).canAuthenticate()).isEqualTo(expected);
	}

	@Test
	@DisplayName("로그인 실패 예외의 문구는 사유를 구분하지 않는다")
	void invalid_credentials_message() {
		assertThat(new InvalidCredentialsException()).hasMessage("아이디 또는 비밀번호가 올바르지 않습니다.");
	}

}

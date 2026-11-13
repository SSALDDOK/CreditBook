package com.creditbook.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

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

	private static final UUID STORE_ID = UUID.fromString("00000000-0000-0000-0000-000000005001");

	private static final Instant CREATED_AT = Instant.parse("2026-10-01T00:00:00Z");
	private static final String HASH = "$2a$10$abcdefghijklmnopqrstuuJ8m0zW8V0u1Yy3s3b4c5d6e7f8g9h0i";

	@Test
	@Tag("REQ-29")
	@Tag("REQ-30")
	@DisplayName("매장 개설로 만든 사장 계정은 활성 상태이고 비밀번호를 바꾸라고 하지 않는다")
	void owner_is_active_and_does_not_need_password_change() {
		// when
		Employee employee = Employee.createOwner(STORE_ID, "owner", HASH, "김사장", CREATED_AT);

		// then
		assertThat(employee.getId()).isNotNull();
		assertThat(employee.getLoginId()).isEqualTo("owner");
		assertThat(employee.getPasswordHash()).isEqualTo(HASH);
		assertThat(employee.getName()).isEqualTo("김사장");
		assertThat(employee.getRole()).isEqualTo(Role.ADMIN);
		assertThat(employee.isActive()).isTrue();
		assertThat(employee.canLogin()).isTrue();
		assertThat(employee.mustChangePassword()).isFalse();
		assertThat(employee.getLastLoginAt()).isNull();
		assertThat(employee.getCreatedAt()).isEqualTo(CREATED_AT);
		assertThat(employee.getUpdatedAt()).isEqualTo(CREATED_AT);
	}

	@Test
	@Tag("REQ-30")
	@DisplayName("사장이 추가한 직원은 STAFF 권한의 활성 계정이고 처음 받은 비밀번호를 바꿔야 한다")
	void staff_is_active_and_must_change_password() {
		// when
		Employee employee = Employee.createStaff(STORE_ID, "staff1", HASH, "이직원", CREATED_AT);

		// then
		assertThat(employee.getId()).isNotNull();
		assertThat(employee.getLoginId()).isEqualTo("staff1");
		assertThat(employee.getPasswordHash()).isEqualTo(HASH);
		assertThat(employee.getName()).isEqualTo("이직원");
		assertThat(employee.getRole()).isEqualTo(Role.STAFF);
		assertThat(employee.isActive()).isTrue();
		assertThat(employee.mustChangePassword()).isTrue();
		assertThat(employee.getFailedLoginCount()).isZero();
		assertThat(employee.getLockedUntil()).isNull();
		assertThat(employee.getCreatedAt()).isEqualTo(CREATED_AT);
		assertThat(employee.getUpdatedAt()).isEqualTo(CREATED_AT);
	}

	@Test
	@DisplayName("로그인에 성공하면 마지막 로그인 시각만 바뀌고 프로필 변경 시각은 그대로다")
	void record_login_changes_only_last_login_at() {
		// given
		Employee employee = Employee.createStaff(STORE_ID, "staff1", HASH, "이직원", CREATED_AT);
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
		String text = Employee.createOwner(STORE_ID, "owner", HASH, "김사장", CREATED_AT).toString();

		// then
		assertThat(text).doesNotContain(HASH).doesNotContain("owner").contains("ADMIN");
	}

	@ParameterizedTest(name = "[{index}] \"{0}\"")
	@NullAndEmptySource
	@ValueSource(strings = { "  " })
	@DisplayName("아이디·비밀번호 해시·이름이 비어 있으면 직원을 만들 수 없다")
	void blank_fields_are_rejected(String blank) {
		assertThatThrownBy(() -> Employee.createOwner(STORE_ID, blank, HASH, "김사장", CREATED_AT))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> Employee.createOwner(STORE_ID, "owner", blank, "김사장", CREATED_AT))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> Employee.createOwner(STORE_ID, "owner", HASH, blank, CREATED_AT))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> Employee.createStaff(STORE_ID, blank, HASH, "이직원", CREATED_AT))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> Employee.createStaff(STORE_ID, "staff1", blank, "이직원", CREATED_AT))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> Employee.createStaff(STORE_ID, "staff1", HASH, blank, CREATED_AT))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@Tag("REQ-29")
	@DisplayName("이름은 앞뒤 공백을 빼고 저장된다")
	void name_is_stripped() {
		// when
		Employee employee = Employee.createOwner(STORE_ID, "kim.owner", HASH, "  김사장 ", CREATED_AT);

		// then
		assertThat(employee.getName()).isEqualTo("김사장");
	}

	@Test
	@Tag("REQ-29")
	@DisplayName("이름은 앞뒤 공백 제거 후 50자까지 저장되고 51자이면 직원을 만들 수 없다")
	void name_longer_than_50_is_rejected() {
		// given
		String fifty = " " + "가".repeat(50) + " ";

		// when / then
		assertThat(Employee.createOwner(STORE_ID, "kim.owner", HASH, fifty, CREATED_AT).getName()).hasSize(50);
		assertThatThrownBy(() -> Employee.createOwner(STORE_ID, "kim.owner", HASH, "가".repeat(51), CREATED_AT))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("생성 시각이 없으면 직원을 만들 수 없다")
	void null_time_is_rejected() {
		assertThatThrownBy(() -> Employee.createOwner(STORE_ID, "owner", HASH, "김사장", null))
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> Employee.createStaff(STORE_ID, "staff1", HASH, "이직원", null))
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
		assertThat(new EmployeeAuthStatus(STORE_ID, active, Role.STAFF, revoked, false).canAuthenticate()).isEqualTo(expected);
	}

	@Test
	@DisplayName("로그인 실패 예외의 문구는 사유를 구분하지 않는다")
	void invalid_credentials_message() {
		assertThat(new InvalidCredentialsException()).hasMessage("아이디 또는 비밀번호가 올바르지 않습니다.");
	}

}

package com.creditbook.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.creditbook.auth.domain.AdminAccountProtectedException;
import com.creditbook.auth.domain.DuplicateLoginIdException;
import com.creditbook.auth.domain.Employee;
import com.creditbook.auth.domain.EmployeeNotFoundException;
import com.creditbook.auth.domain.EmployeeRepository;
import com.creditbook.auth.domain.InitialPasswordGenerator;
import com.creditbook.auth.domain.Role;

/**
 * 사장의 직원 계정 관리 유스케이스 (REQ-30). 저장소는 목, 비밀번호 해시는 실제 BCrypt(낮은 비용 계수), 초기 비밀번호는 시드 고정 난수.
 * 행 잠금·유니크 제약의 실제 DB 동작은 통합 테스트가 본다.
 */
@Tag("REQ-30")
@ExtendWith(OutputCaptureExtension.class)
class EmployeeManagementServiceTest {

	private static final UUID STORE_ID = UUID.fromString("00000000-0000-0000-0000-000000005001");

	private static final Instant NOW = Instant.parse("2026-11-03T02:00:00Z");
	private static final String HASH = "$2a$04$abcdefghijklmnopqrstuuJ8m0zW8V0u1Yy3s3b4c5d6e7f8g9h0";

	private final EmployeeRepository employeeRepository = mock(EmployeeRepository.class);
	private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);
	private final EmployeeManagementService service = new EmployeeManagementService(employeeRepository,
			passwordEncoder, new InitialPasswordGenerator(new Random(11L)), Clock.fixed(NOW, ZoneOffset.UTC));

	private final Employee owner = Employee.createOwner(STORE_ID, "kim.owner", HASH, "김사장", NOW.minusSeconds(3600));
	private final Employee staff = Employee.createStaff(STORE_ID, "lee.staff", HASH, "이직원", NOW.minusSeconds(1800));

	private void given_target(Employee target) {
		given(employeeRepository.findByIdAndStoreIdForUpdate(target.getId(), STORE_ID)).willReturn(Optional.of(target));
	}

	// ── 목록 ──

	@Test
	@DisplayName("직원 목록은 저장소가 돌려준 순서 그대로 이름·아이디·권한·활성 여부·마지막 로그인을 싣는다")
	void list_maps_employees_in_order() {
		// given
		staff.recordLogin(NOW);
		given(employeeRepository.findAllForManagement(STORE_ID)).willReturn(List.of(owner, staff));

		// when
		List<EmployeeSummary> list = service.list(STORE_ID);

		// then
		assertThat(list).containsExactly(
				new EmployeeSummary(owner.getId(), "김사장", "kim.owner", Role.ADMIN, true, null),
				new EmployeeSummary(staff.getId(), "이직원", "lee.staff", Role.STAFF, true, NOW));
	}

	// ── 직원 추가 ──

	@Test
	@DisplayName("직원을 추가하면 STAFF 권한·비밀번호 변경 필요 상태로 저장하고 초기 비밀번호 원문은 결과로만 한 번 돌려준다")
	void add_staff_creates_staff_with_initial_password(CapturedOutput output) {
		// given
		UUID ownerId = owner.getId();
		given(employeeRepository.existsByLoginId("park.staff")).willReturn(false);
		ArgumentCaptor<Employee> saved = ArgumentCaptor.forClass(Employee.class);

		// when
		StaffAddedResult result = service.addStaff(STORE_ID, " 박직원 ", "park.staff", ownerId);

		// then
		verify(employeeRepository).add(saved.capture());
		Employee created = saved.getValue();
		assertThat(created.getRole()).isEqualTo(Role.STAFF);
		assertThat(created.mustChangePassword()).isTrue();
		assertThat(created.isActive()).isTrue();
		assertThat(created.getName()).isEqualTo("박직원");
		assertThat(created.getCreatedAt()).isEqualTo(NOW);
		assertThat(result.employeeId()).isEqualTo(created.getId());
		assertThat(result.name()).isEqualTo("박직원");
		assertThat(result.loginId()).isEqualTo("park.staff");
		assertThat(result.role()).isEqualTo(Role.STAFF);
		assertThat(result.initialPassword()).matches("^[^-]{4}-[^-]{4}-[^-]{4}$");
		// DB 에는 해시만
		assertThat(created.getPasswordHash()).isNotEqualTo(result.initialPassword()).startsWith("$2");
		assertThat(passwordEncoder.matches(result.initialPassword(), created.getPasswordHash())).isTrue();
		// 로그에는 직원 ID 만
		assertThat(output.getOut())
				.contains("staff added: employeeId=" + created.getId() + ", performedBy=" + ownerId)
				.doesNotContain(result.initialPassword()).doesNotContain("park.staff").doesNotContain("박직원");
		assertThat(result.toString()).doesNotContain(result.initialPassword());
	}

	@Test
	@DisplayName("같은 아이디의 직원(비활성 포함)이 있으면 409 로 거절하고 저장하지 않는다")
	void duplicate_login_id_is_rejected_before_insert(CapturedOutput output) {
		// given
		given(employeeRepository.existsByLoginId("lee.staff")).willReturn(true);

		// when / then
		assertThatThrownBy(() -> service.addStaff(STORE_ID, "이직원2", "lee.staff", owner.getId()))
				.isInstanceOf(DuplicateLoginIdException.class)
				.hasMessage("이미 사용 중인 아이디입니다.");
		verify(employeeRepository, never()).add(any());
		assertThat(output.getOut()).contains("staff add rejected: reason=duplicate login id")
				.doesNotContain("lee.staff");
	}

	@Test
	@DisplayName("같은 아이디로 동시에 추가해 사전 확인을 함께 지나가도 늦은 쪽은 409 로 거절된다")
	void concurrent_duplicate_login_id_is_rejected() {
		// given
		given(employeeRepository.existsByLoginId("same.id")).willReturn(false);
		willThrow(new DuplicateLoginIdException()).given(employeeRepository).add(any());

		// when / then
		assertThatThrownBy(() -> service.addStaff(STORE_ID, "동시", "same.id", owner.getId()))
				.isInstanceOf(DuplicateLoginIdException.class);
	}

	@Test
	@DisplayName("처리자를 모르면 직원을 추가하지 않는다")
	void add_staff_requires_performer() {
		assertThatThrownBy(() -> service.addStaff(STORE_ID, "박직원", "park.staff", null)).isInstanceOf(NullPointerException.class);
		verify(employeeRepository, never()).add(any());
	}

	// ── 비밀번호 초기화 ──

	@Test
	@DisplayName("직원 비밀번호를 초기화하면 새 초기 비밀번호를 돌려주고 다음 로그인 뒤 바꾸게 하며 잠금을 푼다")
	void reset_password(CapturedOutput output) {
		// given — 첫 비밀번호를 바꾼 뒤 잠긴 직원
		staff.changePassword(HASH, NOW.minusSeconds(900));
		for (int i = 0; i < 5; i++) {
			staff.recordLoginFailure(NOW.minusSeconds(60), 5, Duration.ofMinutes(5));
		}
		given_target(staff);

		// when
		PasswordResetResult result = service.resetPassword(STORE_ID, staff.getId(), owner.getId());

		// then
		assertThat(result.employeeId()).isEqualTo(staff.getId());
		assertThat(passwordEncoder.matches(result.initialPassword(), staff.getPasswordHash())).isTrue();
		assertThat(staff.mustChangePassword()).isTrue();
		assertThat(staff.isLocked(NOW)).isFalse();
		assertThat(staff.getFailedLoginCount()).isZero();
		assertThat(output.getOut())
				.contains("employee password reset: employeeId=" + staff.getId() + ", performedBy=" + owner.getId())
				.doesNotContain(result.initialPassword());
		assertThat(result.toString()).doesNotContain(result.initialPassword());
	}

	@Test
	@DisplayName("초기화할 때마다 다른 초기 비밀번호가 나온다")
	void each_reset_gives_new_password() {
		// given
		given_target(staff);

		// when
		String first = service.resetPassword(STORE_ID, staff.getId(), owner.getId()).initialPassword();
		String second = service.resetPassword(STORE_ID, staff.getId(), owner.getId()).initialPassword();

		// then
		assertThat(first).isNotEqualTo(second);
		assertThat(passwordEncoder.matches(first, staff.getPasswordHash())).isFalse();
	}

	@Test
	@DisplayName("비활성 직원도 비밀번호를 초기화할 수 있다")
	void inactive_staff_can_be_reset() {
		// given
		staff.deactivate(NOW);
		given_target(staff);

		// when
		PasswordResetResult result = service.resetPassword(STORE_ID, staff.getId(), owner.getId());

		// then
		assertThat(passwordEncoder.matches(result.initialPassword(), staff.getPasswordHash())).isTrue();
		assertThat(staff.isActive()).isFalse();
	}

	@Test
	@DisplayName("없는 직원의 비밀번호 초기화·비활성화는 404 로 거절한다")
	void unknown_employee_is_not_found(CapturedOutput output) {
		// given
		UUID unknown = UUID.randomUUID();
		given(employeeRepository.findByIdAndStoreIdForUpdate(unknown, STORE_ID)).willReturn(Optional.empty());

		// when / then
		assertThatThrownBy(() -> service.resetPassword(STORE_ID, unknown, owner.getId()))
				.isInstanceOf(EmployeeNotFoundException.class);
		assertThatThrownBy(() -> service.deactivate(STORE_ID, unknown, owner.getId()))
				.isInstanceOf(EmployeeNotFoundException.class);
		assertThat(output.getOut()).contains("reason=not found, employeeId=" + unknown);
	}

	@Test
	@DisplayName("사장 계정(본인)의 비밀번호 초기화·비활성화는 409 로 거절하고 아무것도 바꾸지 않는다")
	void owner_account_is_protected(CapturedOutput output) {
		// given
		given_target(owner);

		// when / then
		assertThatThrownBy(() -> service.resetPassword(STORE_ID, owner.getId(), owner.getId()))
				.isInstanceOf(AdminAccountProtectedException.class);
		assertThatThrownBy(() -> service.deactivate(STORE_ID, owner.getId(), owner.getId()))
				.isInstanceOf(AdminAccountProtectedException.class);
		assertThat(owner.getPasswordHash()).isEqualTo(HASH);
		assertThat(owner.isActive()).isTrue();
		assertThat(output.getOut()).contains("reason=protected account, employeeId=" + owner.getId());
	}

	// ── 비활성화 ──

	@Test
	@DisplayName("직원을 비활성화한다")
	void deactivate_staff(CapturedOutput output) {
		// given
		given_target(staff);

		// when
		service.deactivate(STORE_ID, staff.getId(), owner.getId());

		// then
		assertThat(staff.isActive()).isFalse();
		assertThat(output.getOut())
				.contains("employee deactivated: employeeId=" + staff.getId() + ", performedBy=" + owner.getId());
	}

	@Test
	@DisplayName("이미 비활성인 직원을 다시 비활성화해도 거절하지 않고 그대로 둔다")
	void deactivate_inactive_staff_is_noop(CapturedOutput output) {
		// given
		staff.deactivate(NOW.minusSeconds(60));
		given_target(staff);

		// when
		service.deactivate(STORE_ID, staff.getId(), owner.getId());

		// then
		assertThat(staff.isActive()).isFalse();
		assertThat(staff.getUpdatedAt()).isEqualTo(NOW.minusSeconds(60));
		assertThat(output.getOut()).contains("employee already inactive: employeeId=" + staff.getId());
	}

}

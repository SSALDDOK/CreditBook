package com.creditbook.store.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.creditbook.auth.domain.Employee;
import com.creditbook.auth.domain.EmployeeRepository;
import com.creditbook.auth.domain.Role;
import com.creditbook.store.domain.InvalidStoreNameException;
import com.creditbook.store.domain.SetupAlreadyCompletedException;
import com.creditbook.store.domain.StoreProfile;
import com.creditbook.store.domain.StoreProfileRepository;

/**
 * 매장 개설 유스케이스 (REQ-29). 저장소는 목, 비밀번호 해시는 실제 BCrypt(테스트용 낮은 비용 계수)를 쓴다.
 * 한 트랜잭션·동시 요청의 실제 DB 동작은 통합 테스트(TC-25)가 본다.
 */
@Tag("REQ-29")
@ExtendWith(OutputCaptureExtension.class)
class StoreSetupServiceTest {

	private static final Instant NOW = Instant.parse("2026-10-30T01:00:00Z");

	private final EmployeeRepository employeeRepository = mock(EmployeeRepository.class);
	private final StoreProfileRepository storeProfileRepository = mock(StoreProfileRepository.class);
	private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);
	private final StoreSetupService service = new StoreSetupService(employeeRepository, storeProfileRepository,
			passwordEncoder, Clock.fixed(NOW, ZoneOffset.UTC));

	@Test
	@DisplayName("직원 계정이 하나도 없으면 매장 개설이 가능하고, 하나라도 있으면 불가능하다")
	void setup_available_only_without_accounts() {
		// given / when / then
		given(employeeRepository.existsAny()).willReturn(false);
		assertThat(service.isSetupAvailable()).isTrue();

		given(employeeRepository.existsAny()).willReturn(true);
		assertThat(service.isSetupAvailable()).isFalse();
	}

	@Test
	@DisplayName("계정 0개 상태에서 매장 개설 — 매장을 먼저 저장하고 해시된 비밀번호로 ADMIN 계정을 만든다")
	void setup_creates_store_then_admin(CapturedOutput output) {
		// given
		given(employeeRepository.existsAny()).willReturn(false);

		// when
		StoreSetupResult result = service.setUp(" 카페 새벽 ", " 김사장 ", "kim.owner", "s3cure-pass");

		// then — 매장 INSERT 가 직원 INSERT 보다 먼저 (동시 요청은 매장 단계에서 막힌다)
		ArgumentCaptor<StoreProfile> store = ArgumentCaptor.forClass(StoreProfile.class);
		ArgumentCaptor<Employee> admin = ArgumentCaptor.forClass(Employee.class);
		InOrder order = inOrder(employeeRepository, storeProfileRepository);
		order.verify(employeeRepository).existsAny();
		order.verify(storeProfileRepository).add(store.capture());
		order.verify(employeeRepository).add(admin.capture());

		assertThat(store.getValue().getName()).isEqualTo("카페 새벽");
		assertThat(store.getValue().getCreatedAt()).isEqualTo(NOW);

		Employee created = admin.getValue();
		assertThat(created.getLoginId()).isEqualTo("kim.owner");
		assertThat(created.getName()).isEqualTo("김사장");
		assertThat(created.getRole()).isEqualTo(Role.ADMIN);
		assertThat(created.isActive()).isTrue();
		assertThat(created.getCreatedAt()).isEqualTo(NOW);
		assertThat(created.getPasswordHash()).isNotEqualTo("s3cure-pass").startsWith("$2");
		assertThat(passwordEncoder.matches("s3cure-pass", created.getPasswordHash())).isTrue();

		assertThat(result).isEqualTo(new StoreSetupResult("카페 새벽", created.getId(), "kim.owner", "김사장", Role.ADMIN));

		// 로그에는 새 ADMIN 직원 ID 만 — 매장명·아이디·이름·비밀번호는 남지 않는다
		assertThat(output.getOut()).contains("store setup completed: adminEmployeeId=" + created.getId())
				.doesNotContain("카페 새벽").doesNotContain("kim.owner").doesNotContain("김사장")
				.doesNotContain("s3cure-pass").doesNotContain(created.getPasswordHash());
	}

	@Test
	@DisplayName("계정이 이미 있으면 매장 개설을 거절하고 매장·계정을 만들지 않는다")
	void setup_rejected_when_account_exists() {
		// given
		given(employeeRepository.existsAny()).willReturn(true);

		// when / then
		assertThatThrownBy(() -> service.setUp("카페 새벽", "김사장", "kim.owner", "s3cure-pass"))
				.isInstanceOf(SetupAlreadyCompletedException.class);
		verifyNoInteractions(storeProfileRepository);
		verify(employeeRepository, never()).add(any());
	}

	@Test
	@DisplayName("동시 요청이 매장을 먼저 만들었으면 매장 개설을 거절하고 ADMIN 계정을 만들지 않는다")
	void setup_rejected_when_store_singleton_violated() {
		// given — 두 요청 모두 직원 0명을 봤지만 다른 요청의 매장 행이 먼저 들어갔다
		given(employeeRepository.existsAny()).willReturn(false);
		willThrow(new SetupAlreadyCompletedException(new RuntimeException("duplicate key")))
				.given(storeProfileRepository).add(any());

		// when / then
		assertThatThrownBy(() -> service.setUp("카페 새벽", "김사장", "kim.owner", "s3cure-pass"))
				.isInstanceOf(SetupAlreadyCompletedException.class);
		verify(employeeRepository, never()).add(any());
	}

	@Test
	@DisplayName("매장명이 규칙에 맞지 않으면 아무것도 저장하지 않는다")
	void setup_rejected_when_store_name_invalid() {
		// given
		given(employeeRepository.existsAny()).willReturn(false);

		// when / then
		assertThatThrownBy(() -> service.setUp("   ", "김사장", "kim.owner", "s3cure-pass"))
				.isInstanceOf(InvalidStoreNameException.class);
		verifyNoInteractions(storeProfileRepository);
		verify(employeeRepository, never()).add(any());
	}

}

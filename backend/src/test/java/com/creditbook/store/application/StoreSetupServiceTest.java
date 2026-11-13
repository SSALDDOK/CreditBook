package com.creditbook.store.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
import java.util.Optional;
import java.util.UUID;

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

import com.creditbook.auth.domain.DuplicateLoginIdException;
import com.creditbook.auth.domain.Employee;
import com.creditbook.auth.domain.EmployeeRepository;
import com.creditbook.auth.domain.Role;
import com.creditbook.store.domain.InvalidInviteCodeException;
import com.creditbook.store.domain.InvalidStoreNameException;
import com.creditbook.store.domain.Store;
import com.creditbook.store.domain.StoreInviteCode;
import com.creditbook.store.domain.StoreInviteCodeRepository;
import com.creditbook.store.domain.StoreRepository;

/**
 * 초대 코드 가입 유스케이스 (REQ-29·36). 저장소는 목, 비밀번호 해시는 실제 BCrypt(테스트용 낮은 비용 계수)를 쓴다.
 * 한 트랜잭션(실패 시 차감 되돌림)·조건부 UPDATE·동시 요청의 실제 DB 동작은 통합 테스트가 본다.
 */
@Tag("REQ-29")
@Tag("REQ-36")
@ExtendWith(OutputCaptureExtension.class)
class StoreSetupServiceTest {

	private static final Instant NOW = Instant.parse("2026-11-13T01:00:00Z");
	private static final UUID INVITE_CODE_ID = UUID.fromString("0f1e2d3c-4b5a-4968-8776-655443322110");
	private static final String RAW_CODE = "abcd-efgh-2345";

	private final StoreInviteCodeRepository storeInviteCodeRepository = mock(StoreInviteCodeRepository.class);
	private final EmployeeRepository employeeRepository = mock(EmployeeRepository.class);
	private final StoreRepository storeRepository = mock(StoreRepository.class);
	private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);
	private final StoreSetupService service = new StoreSetupService(storeInviteCodeRepository, employeeRepository,
			storeRepository, passwordEncoder, Clock.fixed(NOW, ZoneOffset.UTC));

	@Test
	@DisplayName("초대 코드가 맞으면 코드 사용 횟수를 차감하고, 아이디를 확인한 뒤, 매장과 해시된 비밀번호의 사장 계정을 만든다")
	void signup_claims_code_then_creates_store_and_admin(CapturedOutput output) {
		// given — 코드는 정규화한 값의 해시로 찾는다
		given(storeInviteCodeRepository.claimUse(StoreInviteCode.hashOf(RAW_CODE))).willReturn(Optional.of(INVITE_CODE_ID));
		given(employeeRepository.existsByLoginId("kim.owner")).willReturn(false);

		// when
		StoreSetupResult result = service.setUp(RAW_CODE, " 카페 새벽 ", " 김사장 ", "kim.owner", "s3cure-pass");

		// then — 차감 → 아이디 확인 → 매장 INSERT → 사장 INSERT 순서
		ArgumentCaptor<Store> store = ArgumentCaptor.forClass(Store.class);
		ArgumentCaptor<Employee> admin = ArgumentCaptor.forClass(Employee.class);
		InOrder order = inOrder(storeInviteCodeRepository, employeeRepository, storeRepository);
		order.verify(storeInviteCodeRepository).claimUse(StoreInviteCode.hashOf(RAW_CODE));
		order.verify(employeeRepository).existsByLoginId("kim.owner");
		order.verify(storeRepository).add(store.capture());
		order.verify(employeeRepository).add(admin.capture());

		assertThat(store.getValue().getName()).isEqualTo("카페 새벽");
		assertThat(store.getValue().getInviteCodeId()).isEqualTo(INVITE_CODE_ID);
		assertThat(store.getValue().getCreatedAt()).isEqualTo(NOW);

		Employee created = admin.getValue();
		assertThat(created.getStoreId()).isEqualTo(store.getValue().getId()); // 사장은 새 매장에 속한다 (REQ-36)
		assertThat(created.getLoginId()).isEqualTo("kim.owner");
		assertThat(created.getName()).isEqualTo("김사장");
		assertThat(created.getRole()).isEqualTo(Role.ADMIN);
		assertThat(created.isActive()).isTrue();
		// 사장이 직접 정한 비밀번호라 바꾸라고 하지 않는다 (REQ-30)
		assertThat(created.mustChangePassword()).isFalse();
		assertThat(created.getCreatedAt()).isEqualTo(NOW);
		assertThat(created.getPasswordHash()).isNotEqualTo("s3cure-pass").startsWith("$2");
		assertThat(passwordEncoder.matches("s3cure-pass", created.getPasswordHash())).isTrue();

		assertThat(result).isEqualTo(new StoreSetupResult("카페 새벽", created.getId(), "kim.owner", "김사장", Role.ADMIN));

		// 로그에는 매장·사장·초대 코드 id 만 — 코드 원문·해시, 매장명·아이디·이름·비밀번호는 남지 않는다
		assertThat(output.getOut())
				.contains("store signup completed: storeId=" + store.getValue().getId() + ", adminEmployeeId="
						+ created.getId() + ", inviteCodeId=" + INVITE_CODE_ID)
				.doesNotContain(RAW_CODE).doesNotContain(StoreInviteCode.normalize(RAW_CODE))
				.doesNotContain(StoreInviteCode.hashOf(RAW_CODE))
				.doesNotContain("카페 새벽").doesNotContain("kim.owner").doesNotContain("김사장")
				.doesNotContain("s3cure-pass").doesNotContain(created.getPasswordHash());
	}

	@Test
	@DisplayName("공백·하이픈·대소문자만 다르게 입력해도 같은 코드로 찾는다")
	void signup_uses_normalized_code_hash() {
		// given
		given(storeInviteCodeRepository.claimUse(anyString())).willReturn(Optional.of(INVITE_CODE_ID));

		// when
		service.setUp(" ABCD EFGH 2345 ", "카페 새벽", "김사장", "kim.owner", "s3cure-pass");

		// then
		verify(storeInviteCodeRepository).claimUse(StoreInviteCode.hashOf(RAW_CODE));
	}

	@Test
	@DisplayName("없는·꺼진·다 쓴 초대 코드면(차감 0행) 가입을 거절하고 아이디 확인·매장·계정 저장을 하지 않는다")
	void signup_rejected_when_code_cannot_be_claimed() {
		// given
		given(storeInviteCodeRepository.claimUse(anyString())).willReturn(Optional.empty());

		// when / then
		assertThatThrownBy(() -> service.setUp(RAW_CODE, "카페 새벽", "김사장", "kim.owner", "s3cure-pass"))
				.isInstanceOf(InvalidInviteCodeException.class)
				.hasMessage("초대 코드를 확인해 주세요.");
		verifyNoInteractions(employeeRepository, storeRepository);
	}

	@Test
	@DisplayName("아이디가 이미 있으면(다른 매장·비활성 포함) 가입을 거절하고 매장·계정을 만들지 않는다")
	void signup_rejected_when_login_id_exists() {
		// given
		given(storeInviteCodeRepository.claimUse(anyString())).willReturn(Optional.of(INVITE_CODE_ID));
		given(employeeRepository.existsByLoginId("kim.owner")).willReturn(true);

		// when / then — 차감은 트랜잭션이 되돌린다 (예외가 RuntimeException 이라 롤백)
		assertThatThrownBy(() -> service.setUp(RAW_CODE, "카페 새벽", "김사장", "kim.owner", "s3cure-pass"))
				.isInstanceOf(DuplicateLoginIdException.class)
				.isInstanceOf(RuntimeException.class);
		verifyNoInteractions(storeRepository);
		verify(employeeRepository, never()).add(any());
	}

	@Test
	@DisplayName("같은 아이디 동시 가입으로 사장 계정 저장이 아이디 중복에 걸리면 그 예외를 그대로 던진다")
	void signup_propagates_concurrent_duplicate_login_id() {
		// given — 사전 확인은 함께 지나갔고, 저장할 때 ux_employees_login_id 가 막은 경우
		given(storeInviteCodeRepository.claimUse(anyString())).willReturn(Optional.of(INVITE_CODE_ID));
		willThrow(new DuplicateLoginIdException()).given(employeeRepository).add(any());

		// when / then
		assertThatThrownBy(() -> service.setUp(RAW_CODE, "카페 새벽", "김사장", "kim.owner", "s3cure-pass"))
				.isInstanceOf(DuplicateLoginIdException.class);
	}

	@Test
	@DisplayName("매장명이 규칙에 맞지 않으면 매장·계정을 저장하지 않는다")
	void signup_rejected_when_store_name_invalid() {
		// given
		given(storeInviteCodeRepository.claimUse(anyString())).willReturn(Optional.of(INVITE_CODE_ID));

		// when / then
		assertThatThrownBy(() -> service.setUp(RAW_CODE, "   ", "김사장", "kim.owner", "s3cure-pass"))
				.isInstanceOf(InvalidStoreNameException.class);
		verifyNoInteractions(storeRepository);
		verify(employeeRepository, never()).add(any());
	}

}

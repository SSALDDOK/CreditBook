package com.creditbook.customer.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.creditbook.prepaid.domain.PrepaidAccount;

/**
 * 고객 정보 수정 도메인 규칙 (REQ-3). 버전 비교 → 값 정규화·검증 → 바뀐 칸 계산 → (연락처가 바뀔 때만) 같은 번호 확인 → 상태 변경.
 * 스프링 컨텍스트 없이 순수 객체로 검증한다. 새로 등록한 고객의 버전은 0 이다 (JPA 가 UPDATE 때 올린다).
 */
@Tag("REQ-3")
class CustomerEditTest {

	private static final UUID STORE_ID = UUID.fromString("00000000-0000-0000-0000-000000005001");

	private static final Instant REGISTERED_AT = Instant.parse("2026-11-01T01:00:00Z");
	private static final Instant NOW = Instant.parse("2026-11-16T03:00:00Z");

	private static Customer newCustomer() {
		return Customer.register(STORE_ID, "김단골", "01012345678", "아메리카노", REGISTERED_AT);
	}

	/** 불린 연락처를 모아 두는 확인 — 몇 번, 어떤 번호로 불렸는지 본다. */
	private static final class RecordingCheck implements PhoneChangeCheck {

		final List<String> calls = new ArrayList<>();

		@Override
		public void check(String newPhone) {
			calls.add(newPhone);
		}

	}

	@Test
	@DisplayName("화면이 받은 버전과 지금 버전이 다르면 값이 같아도 거절하고 아무것도 바꾸지 않는다")
	void edit_rejects_stale_version_even_when_values_are_same() {
		// given: 지금 버전은 0
		Customer customer = newCustomer();
		RecordingCheck check = new RecordingCheck();

		// when / then: 같은 값을 버전 1로 보낸다
		assertThatThrownBy(() -> customer.edit(1L, "김단골", "01012345678", "아메리카노", NOW, check))
				.isInstanceOfSatisfying(CustomerConcurrentModificationException.class, ex -> {
					assertThat(ex.getCustomerId()).isEqualTo(customer.getId());
					assertThat(ex.getExpectedVersion()).isEqualTo(1L);
					assertThat(ex.getCurrentVersion()).isZero();
				});
		assertThat(customer.getUpdatedAt()).isEqualTo(REGISTERED_AT);
		assertThat(check.calls).isEmpty();
	}

	@Test
	@DisplayName("버전 비교가 값 검증보다 먼저다 — 버전이 낡았으면 값이 틀려도 버전 거절로 응답한다")
	void edit_checks_version_before_values() {
		// given
		Customer customer = newCustomer();

		// when / then
		assertThatThrownBy(() -> customer.edit(1L, "   ", "123", "메".repeat(201), NOW, PhoneChangeCheck.NONE))
				.isInstanceOf(CustomerConcurrentModificationException.class);
	}

	@Test
	@DisplayName("모든 칸을 보내지 않으면 바뀐 칸이 없고 상태·변경 시각을 그대로 둔다")
	void edit_with_all_null_changes_nothing() {
		// given
		Customer customer = newCustomer();
		RecordingCheck check = new RecordingCheck();

		// when
		CustomerChanges changes = customer.edit(0L, null, null, null, NOW, check);

		// then
		assertThat(changes).isEqualTo(CustomerChanges.NONE);
		assertThat(changes.hasAny()).isFalse();
		assertThat(changes.changedFieldNames()).isEmpty();
		assertThat(customer.getName()).isEqualTo("김단골");
		assertThat(customer.getPhone()).isEqualTo("01012345678");
		assertThat(customer.getMemo()).isEqualTo("아메리카노");
		assertThat(customer.getUpdatedAt()).isEqualTo(REGISTERED_AT);
		assertThat(check.calls).isEmpty();
	}

	@Test
	@DisplayName("지금과 같은 값을 보내면 바뀐 칸이 없고 상태·변경 시각을 그대로 둔다")
	void edit_with_same_values_changes_nothing() {
		// given
		Customer customer = newCustomer();
		RecordingCheck check = new RecordingCheck();

		// when
		CustomerChanges changes = customer.edit(0L, "김단골", "01012345678", "아메리카노", NOW, check);

		// then
		assertThat(changes.hasAny()).isFalse();
		assertThat(customer.getUpdatedAt()).isEqualTo(REGISTERED_AT);
		assertThat(check.calls).isEmpty();
	}

	@Test
	@DisplayName("이름이 앞뒤 공백만 다르면 바뀐 것이 아니다")
	void edit_name_differing_only_in_surrounding_spaces_is_not_a_change() {
		// given
		Customer customer = newCustomer();

		// when
		CustomerChanges changes = customer.edit(0L, "  김단골 ", null, null, NOW, PhoneChangeCheck.NONE);

		// then
		assertThat(changes.hasAny()).isFalse();
		assertThat(customer.getName()).isEqualTo("김단골");
		assertThat(customer.getUpdatedAt()).isEqualTo(REGISTERED_AT);
	}

	@Test
	@DisplayName("이름을 바꾸면 앞뒤 공백을 지워 저장하고 이름만 바뀐 칸으로 알리며 변경 시각을 남긴다")
	void edit_name_strips_and_reports_only_name() {
		// given
		Customer customer = newCustomer();

		// when
		CustomerChanges changes = customer.edit(0L, " 김단골님 ", null, null, NOW, PhoneChangeCheck.NONE);

		// then
		assertThat(changes).isEqualTo(new CustomerChanges(true, false, false));
		assertThat(changes.changedFieldNames()).containsExactly("name");
		assertThat(customer.getName()).isEqualTo("김단골님");
		assertThat(customer.getPhone()).isEqualTo("01012345678");
		assertThat(customer.getMemo()).isEqualTo("아메리카노");
		assertThat(customer.getUpdatedAt()).isEqualTo(NOW);
		assertThat(customer.getCreatedAt()).isEqualTo(REGISTERED_AT);
	}

	@ParameterizedTest(name = "[{index}] 메모 \"{0}\"")
	@ValueSource(strings = { "", "   " })
	@DisplayName("메모를 빈 값이나 공백으로 보내면 메모를 지운다")
	void edit_blank_memo_clears_memo(String memo) {
		// given
		Customer customer = newCustomer();

		// when
		CustomerChanges changes = customer.edit(0L, null, null, memo, NOW, PhoneChangeCheck.NONE);

		// then
		assertThat(changes).isEqualTo(new CustomerChanges(false, false, true));
		assertThat(customer.getMemo()).isNull();
		assertThat(customer.getUpdatedAt()).isEqualTo(NOW);
	}

	@Test
	@DisplayName("메모가 없는 고객에게 빈 메모를 보내면 바뀐 것이 아니다")
	void edit_blank_memo_on_customer_without_memo_is_not_a_change() {
		// given
		Customer customer = Customer.register(STORE_ID, "김단골", "01012345678", REGISTERED_AT);

		// when
		CustomerChanges changes = customer.edit(0L, null, null, "", NOW, PhoneChangeCheck.NONE);

		// then
		assertThat(changes.hasAny()).isFalse();
		assertThat(customer.getMemo()).isNull();
		assertThat(customer.getUpdatedAt()).isEqualTo(REGISTERED_AT);
	}

	@Test
	@DisplayName("메모를 보내지 않으면(null) 메모를 그대로 둔다")
	void edit_null_memo_keeps_memo() {
		// given
		Customer customer = newCustomer();

		// when
		customer.edit(0L, "김새이름", null, null, NOW, PhoneChangeCheck.NONE);

		// then
		assertThat(customer.getMemo()).isEqualTo("아메리카노");
	}

	@Test
	@DisplayName("연락처가 지금과 같으면 바뀐 것이 아니고 같은 번호 확인을 하지 않는다")
	void edit_same_phone_skips_check() {
		// given
		Customer customer = newCustomer();
		RecordingCheck check = new RecordingCheck();

		// when
		CustomerChanges changes = customer.edit(0L, null, "01012345678", "단골", NOW, check);

		// then: 메모만 바뀌었다
		assertThat(changes).isEqualTo(new CustomerChanges(false, false, true));
		assertThat(check.calls).isEmpty();
	}

	@Test
	@DisplayName("연락처가 바뀌면 상태를 바꾸기 전에 새 번호로 같은 번호 확인을 한 번 부른다")
	void edit_changed_phone_calls_check_with_new_phone() {
		// given
		Customer customer = newCustomer();
		RecordingCheck check = new RecordingCheck();

		// when
		CustomerChanges changes = customer.edit(0L, null, "01098765432", null, NOW, check);

		// then
		assertThat(changes).isEqualTo(new CustomerChanges(false, true, false));
		assertThat(check.calls).containsExactly("01098765432");
		assertThat(customer.getPhone()).isEqualTo("01098765432");
		assertThat(customer.getUpdatedAt()).isEqualTo(NOW);
	}

	@Test
	@DisplayName("같은 번호 확인이 거절하면 다른 칸도 바꾸지 않는다")
	void edit_rejected_by_check_changes_nothing() {
		// given
		Customer customer = newCustomer();
		PhoneChangeCheck rejecting = newPhone -> {
			throw new DuplicatePhoneException(List.of(new SamePhoneCustomer(UUID.randomUUID(), "박서연", true)));
		};

		// when / then
		assertThatThrownBy(() -> customer.edit(0L, "김새이름", "01098765432", "", NOW, rejecting))
				.isInstanceOf(DuplicatePhoneException.class);
		assertThat(customer.getName()).isEqualTo("김단골");
		assertThat(customer.getPhone()).isEqualTo("01012345678");
		assertThat(customer.getMemo()).isEqualTo("아메리카노");
		assertThat(customer.getUpdatedAt()).isEqualTo(REGISTERED_AT);
	}

	@Test
	@DisplayName("세 칸이 모두 바뀌면 바뀐 칸 이름을 이름·연락처·메모 순으로 알린다")
	void edit_all_fields_reports_all_names() {
		// given
		Customer customer = newCustomer();

		// when
		CustomerChanges changes = customer.edit(0L, "김새이름", "0212345678", "라떼", NOW, PhoneChangeCheck.NONE);

		// then
		assertThat(changes.changedFieldNames()).containsExactly("name", "phone", "memo");
	}

	@ParameterizedTest(name = "[{index}] 이름 \"{0}\"")
	@ValueSource(strings = { "", "   ", "가나다라마바사아자차카타파하가나다라마바사" })
	@DisplayName("이름이 비었거나 공백뿐이거나 20자를 넘으면 거절하고 아무것도 바꾸지 않는다")
	void edit_rejects_invalid_name(String name) {
		// given
		Customer customer = newCustomer();

		// when / then
		assertThatThrownBy(() -> customer.edit(0L, name, null, "라떼", NOW, PhoneChangeCheck.NONE))
				.isInstanceOf(InvalidCustomerNameException.class);
		assertThat(customer.getName()).isEqualTo("김단골");
		assertThat(customer.getMemo()).isEqualTo("아메리카노");
		assertThat(customer.getUpdatedAt()).isEqualTo(REGISTERED_AT);
	}

	@ParameterizedTest(name = "[{index}] 연락처 \"{0}\"")
	@ValueSource(strings = { "", "01012345", "010123456789", "010-1234-5678", "0101234567a" })
	@DisplayName("연락처가 숫자 9–11자리가 아니면 거절하고 아무것도 바꾸지 않는다")
	void edit_rejects_invalid_phone(String phone) {
		// given
		Customer customer = newCustomer();

		// when / then
		assertThatThrownBy(() -> customer.edit(0L, null, phone, null, NOW, PhoneChangeCheck.NONE))
				.isInstanceOf(InvalidPhoneNumberException.class);
		assertThat(customer.getPhone()).isEqualTo("01012345678");
	}

	@Test
	@DisplayName("메모가 200자(코드 포인트)를 넘으면 거절한다 — 이모지는 한 글자로 센다")
	void edit_rejects_memo_over_200_code_points() {
		// given: 이모지 201개 = 코드 포인트 201, UTF-16 402
		Customer customer = newCustomer();
		String memo = "😀".repeat(201);

		// when / then
		assertThatThrownBy(() -> customer.edit(0L, null, null, memo, NOW, PhoneChangeCheck.NONE))
				.isInstanceOf(InvalidCustomerMemoException.class);
		assertThat(customer.getMemo()).isEqualTo("아메리카노");
	}

	@Test
	@DisplayName("이름 20자·메모 200자(코드 포인트)까지는 고칠 수 있다")
	void edit_accepts_name_20_and_memo_200_code_points() {
		// given: 메모는 이모지 200개 (UTF-16 400)
		Customer customer = newCustomer();
		String name = "가".repeat(20);
		String memo = "😀".repeat(200);

		// when
		CustomerChanges changes = customer.edit(0L, name, null, memo, NOW, PhoneChangeCheck.NONE);

		// then
		assertThat(changes).isEqualTo(new CustomerChanges(true, false, true));
		assertThat(customer.getName()).isEqualTo(name);
		assertThat(customer.getMemo()).isEqualTo(memo);
	}

	@Test
	@DisplayName("비활성 고객도 고칠 수 있고, 고쳐도 비활성으로 남는다")
	void edit_inactive_customer_stays_inactive() {
		// given: 잔액 0원 계좌로 비활성화
		Customer customer = newCustomer();
		customer.deactivate(PrepaidAccount.open(STORE_ID, customer.getId(), REGISTERED_AT), REGISTERED_AT);

		// when
		CustomerChanges changes = customer.edit(0L, "김휴면", null, null, NOW, PhoneChangeCheck.NONE);

		// then
		assertThat(changes.nameChanged()).isTrue();
		assertThat(customer.getName()).isEqualTo("김휴면");
		assertThat(customer.isActive()).isFalse();
	}

}

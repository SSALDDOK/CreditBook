package com.creditbook.customer.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.creditbook.prepaid.domain.ChargePolicy;
import com.creditbook.prepaid.domain.PrepaidAccount;

/**
 * 고객 비활성화 도메인 규칙 (REQ-4). 잔액 판단은 PrepaidAccount, 상태 변경은 Customer 가 한다.
 * 스프링 컨텍스트 없이 순수 객체로 검증한다. 잔액이 있는 계좌는 공개 API(charge·use)로만 만든다.
 */
@Tag("REQ-4")
class CustomerDeactivateTest {

	private static final Instant REGISTERED_AT = Instant.parse("2026-10-01T01:00:00Z");
	private static final Instant NOW = Instant.parse("2026-10-11T03:00:00Z");
	private static final UUID EMPLOYEE_ID = UUID.fromString("00000000-0000-0000-0000-00000000e001");
	/** 준비용 — 1회 한도에 막히지 않고 원하는 잔액을 한 번에 만든다. */
	private static final ChargePolicy SETUP_POLICY = ChargePolicy.ofMaxAmount(1_000_000);

	private static Customer newCustomer() {
		return Customer.register("김단골", "01012345678", REGISTERED_AT);
	}

	private static PrepaidAccount accountOf(Customer customer, long balance) {
		PrepaidAccount account = PrepaidAccount.open(customer.getId(), REGISTERED_AT);
		if (balance > 0) {
			account.charge(BigDecimal.valueOf(balance), SETUP_POLICY, EMPLOYEE_ID, REGISTERED_AT, null);
		}
		return account;
	}

	@Test
	@DisplayName("잔액이 0원인 고객을 비활성화하면 비활성 상태가 되고 변경 시각이 기록된다")
	void deactivate_marks_customer_inactive_when_balance_is_zero() {
		// given
		Customer customer = newCustomer();
		PrepaidAccount account = accountOf(customer, 0);

		// when
		customer.deactivate(account, NOW);

		// then
		assertThat(customer.isActive()).isFalse();
		assertThat(customer.getUpdatedAt()).isEqualTo(NOW);
		assertThat(customer.getCreatedAt()).isEqualTo(REGISTERED_AT);
	}

	@Test
	@DisplayName("충전한 금액을 모두 사용해 잔액이 0원이 된 고객도 비활성화할 수 있다")
	void deactivate_succeeds_after_balance_is_used_up() {
		// given
		Customer customer = newCustomer();
		PrepaidAccount account = accountOf(customer, 5_000);
		account.use(BigDecimal.valueOf(5_000), EMPLOYEE_ID, REGISTERED_AT, null);

		// when
		customer.deactivate(account, NOW);

		// then
		assertThat(account.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(customer.isActive()).isFalse();
	}

	@ParameterizedTest(name = "[{index}] 잔액 {0}원")
	@ValueSource(longs = { 1, 2, 300_000, 300_001 })
	@DisplayName("잔액이 0원이 아닌 고객은 비활성화할 수 없고 현재 잔액을 알려 준다")
	void deactivate_rejects_customer_with_remaining_balance(long balance) {
		// given
		Customer customer = newCustomer();
		PrepaidAccount account = accountOf(customer, balance);

		// when / then
		assertThatThrownBy(() -> customer.deactivate(account, NOW))
				.isInstanceOfSatisfying(CustomerBalanceNotZeroException.class, ex -> {
					assertThat(ex.getCustomerId()).isEqualTo(customer.getId());
					assertThat(ex.getBalance()).isEqualByComparingTo(BigDecimal.valueOf(balance));
				})
				.hasMessage("잔액이 0원인 고객만 비활성화할 수 있습니다.");
		assertThat(customer.isActive()).isTrue();
		assertThat(customer.getUpdatedAt()).isEqualTo(REGISTERED_AT);
	}

	@Test
	@DisplayName("이미 비활성인 고객을 다시 비활성화해도 오류 없이 상태와 변경 시각이 그대로다")
	void deactivate_is_idempotent_for_inactive_customer() {
		// given
		Customer customer = newCustomer();
		PrepaidAccount account = accountOf(customer, 0);
		customer.deactivate(account, NOW);
		Instant later = NOW.plusSeconds(3_600);

		// when
		customer.deactivate(account, later);

		// then
		assertThat(customer.isActive()).isFalse();
		assertThat(customer.getUpdatedAt()).isEqualTo(NOW);
	}

	@Test
	@DisplayName("다른 고객의 계좌를 넘기면 비활성화하지 않는다 (호출자 버그)")
	void deactivate_rejects_account_of_another_customer() {
		// given
		Customer customer = newCustomer();
		PrepaidAccount othersAccount = accountOf(newCustomer(), 0);

		// when / then
		assertThatThrownBy(() -> customer.deactivate(othersAccount, NOW))
				.isInstanceOf(IllegalArgumentException.class);
		assertThat(customer.isActive()).isTrue();
	}

	@Test
	@DisplayName("계좌나 시각이 없으면 비활성화하지 않는다")
	void deactivate_requires_account_and_time() {
		// given
		Customer customer = newCustomer();
		PrepaidAccount account = accountOf(customer, 0);

		// when / then
		assertThatThrownBy(() -> customer.deactivate(null, NOW)).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> customer.deactivate(account, null)).isInstanceOf(NullPointerException.class);
		assertThat(customer.isActive()).isTrue();
	}

}

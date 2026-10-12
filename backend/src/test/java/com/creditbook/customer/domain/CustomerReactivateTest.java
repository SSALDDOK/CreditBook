package com.creditbook.customer.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.creditbook.prepaid.domain.PrepaidAccount;

/**
 * 고객 재활성화·비활성 고객 사용 거부 도메인 규칙 (REQ-33). 스프링 컨텍스트 없이 순수 객체로 검증한다.
 * 비활성 고객은 공개 API(잔액 0원 계좌로 deactivate)로만 만든다.
 */
@Tag("REQ-33")
class CustomerReactivateTest {

	private static final Instant REGISTERED_AT = Instant.parse("2026-10-01T01:00:00Z");
	private static final Instant DEACTIVATED_AT = Instant.parse("2026-10-11T03:00:00Z");
	private static final Instant NOW = Instant.parse("2026-10-12T05:00:00Z");

	private static Customer activeCustomer() {
		return Customer.register("김단골", "01012345678", REGISTERED_AT);
	}

	private static Customer inactiveCustomer() {
		Customer customer = activeCustomer();
		customer.deactivate(PrepaidAccount.open(customer.getId(), REGISTERED_AT), DEACTIVATED_AT);
		return customer;
	}

	@Test
	@DisplayName("비활성 고객을 재활성화하면 활성 상태가 되고 변경 시각이 기록된다")
	void reactivate_marks_inactive_customer_active() {
		// given
		Customer customer = inactiveCustomer();

		// when
		boolean changed = customer.reactivate(NOW);

		// then
		assertThat(changed).isTrue();
		assertThat(customer.isActive()).isTrue();
		assertThat(customer.getUpdatedAt()).isEqualTo(NOW);
		assertThat(customer.getCreatedAt()).isEqualTo(REGISTERED_AT);
	}

	@Test
	@DisplayName("이미 활성인 고객을 재활성화해도 상태와 변경 시각이 그대로다")
	void reactivate_is_noop_for_active_customer() {
		// given
		Customer customer = activeCustomer();

		// when
		boolean changed = customer.reactivate(NOW);

		// then
		assertThat(changed).isFalse();
		assertThat(customer.isActive()).isTrue();
		assertThat(customer.getUpdatedAt()).isEqualTo(REGISTERED_AT);
	}

	@Test
	@DisplayName("재활성화 시각이 없으면 상태를 바꾸지 않는다")
	void reactivate_requires_time() {
		// given
		Customer customer = inactiveCustomer();

		// when / then
		assertThatThrownBy(() -> customer.reactivate(null)).isInstanceOf(NullPointerException.class);
		assertThat(customer.isActive()).isFalse();
	}

	@Test
	@DisplayName("활성 고객은 사용할 수 있다")
	void active_customer_can_use() {
		// given
		Customer customer = activeCustomer();

		// when / then
		assertThatCode(customer::ensureCanUse).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("비활성 고객은 사용할 수 없고 충전하면 다시 활성화된다고 안내한다")
	void inactive_customer_cannot_use() {
		// given
		Customer customer = inactiveCustomer();

		// when / then
		assertThatThrownBy(customer::ensureCanUse)
				.isInstanceOfSatisfying(CustomerInactiveException.class,
						ex -> assertThat(ex.getCustomerId()).isEqualTo(customer.getId()))
				.hasMessage("비활성 고객입니다. 충전하면 다시 활성화됩니다.");
		assertThat(customer.isActive()).isFalse();
	}

	@Test
	@DisplayName("재활성화된 고객은 다시 사용할 수 있다")
	void reactivated_customer_can_use_again() {
		// given
		Customer customer = inactiveCustomer();
		customer.reactivate(NOW);

		// when / then
		assertThatCode(customer::ensureCanUse).doesNotThrowAnyException();
	}

}

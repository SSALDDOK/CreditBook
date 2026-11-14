package com.creditbook.customer.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 고객의 매장 (REQ-36). 고객은 등록한 직원의 매장에 속하고, 매장 없이는 등록되지 않는다.
 */
@Tag("REQ-36")
class CustomerStoreTest {

	private static final Instant REGISTERED_AT = Instant.parse("2026-11-10T01:00:00Z");

	@Test
	@DisplayName("고객은 매장 없이 등록할 수 없다")
	void customer_requires_store() {
		assertThatThrownBy(() -> Customer.register(null, "김단골", "01012345678", REGISTERED_AT))
				.isInstanceOf(NullPointerException.class)
				.hasMessageContaining("storeId");
		assertThatThrownBy(() -> Customer.register(null, "김단골", "01012345678", "메모", REGISTERED_AT))
				.isInstanceOf(NullPointerException.class)
				.hasMessageContaining("storeId");
	}

	@Test
	@DisplayName("등록한 고객은 그 매장에 속한다")
	void registered_customer_belongs_to_store() {
		// given
		UUID storeId = UUID.randomUUID();

		// when
		Customer customer = Customer.register(storeId, "김단골", "01012345678", "단골", REGISTERED_AT);

		// then
		assertThat(customer.getStoreId()).isEqualTo(storeId);
		assertThat(customer.isActive()).isTrue();
	}

}

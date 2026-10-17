package com.creditbook.customer.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 같은 번호 고객 알림 예외 (REQ-1). 기존 고객 목록을 그대로 들고 다니는지만 본다.
 */
@Tag("REQ-1")
class DuplicatePhoneExceptionTest {

	@Test
	@DisplayName("같은 번호 고객 목록을 순서 그대로 담고, 넘긴 목록이 나중에 바뀌어도 영향받지 않는다")
	void keeps_existing_customers_in_order_as_copy() {
		// given
		List<SamePhoneCustomer> source = new ArrayList<>(List.of(
				new SamePhoneCustomer(UUID.randomUUID(), "박서연", true),
				new SamePhoneCustomer(UUID.randomUUID(), "박휴면", false)));

		// when
		DuplicatePhoneException ex = new DuplicatePhoneException(source);
		source.clear();

		// then
		assertThat(ex.getExistingCustomers()).extracting(SamePhoneCustomer::name).containsExactly("박서연", "박휴면");
		assertThat(ex.getMessage()).isEqualTo("같은 번호의 고객이 있습니다.");
	}

	@Test
	@DisplayName("같은 번호 고객이 없으면 이 예외를 만들 수 없다")
	void rejects_empty_list() {
		// when / then
		assertThatThrownBy(() -> new DuplicatePhoneException(List.of()))
				.isInstanceOf(IllegalArgumentException.class);
	}

}

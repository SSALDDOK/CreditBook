package com.creditbook.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 직원의 매장 (REQ-36). 사장은 매장 개설로 연 매장에, 직원은 추가한 사장의 매장에 속한다. 매장을 알 수 없는 인증은 받지 않는다.
 */
@Tag("REQ-36")
class EmployeeStoreTest {

	private static final Instant CREATED_AT = Instant.parse("2026-11-10T01:00:00Z");
	private static final String HASH = "$2a$10$abcdefghijklmnopqrstuuJ8m0zW8V0u1Yy3s3b4c5d6e7f8g9h0i";

	@Test
	@DisplayName("사장·직원 계정은 매장 없이 만들 수 없다")
	void employee_requires_store() {
		assertThatThrownBy(() -> Employee.createOwner(null, "owner", HASH, "김사장", CREATED_AT))
				.isInstanceOf(NullPointerException.class)
				.hasMessageContaining("storeId");
		assertThatThrownBy(() -> Employee.createStaff(null, "staff1", HASH, "이직원", CREATED_AT))
				.isInstanceOf(NullPointerException.class)
				.hasMessageContaining("storeId");
	}

	@Test
	@DisplayName("사장과 그 사장이 추가한 직원은 같은 매장에 속한다")
	void owner_and_staff_belong_to_store() {
		// given
		UUID storeId = UUID.randomUUID();

		// when
		Employee owner = Employee.createOwner(storeId, "owner", HASH, "김사장", CREATED_AT);
		Employee staff = Employee.createStaff(owner.getStoreId(), "staff1", HASH, "이직원", CREATED_AT);

		// then
		assertThat(owner.getStoreId()).isEqualTo(storeId);
		assertThat(staff.getStoreId()).isEqualTo(storeId);
	}

	@Test
	@DisplayName("매장을 알 수 없는 직원 인증 상태로는 요청을 처리하지 않는다")
	void auth_status_without_store_cannot_authenticate() {
		// given
		EmployeeAuthStatus withoutStore = new EmployeeAuthStatus(null, true, Role.STAFF, false, false);
		EmployeeAuthStatus withStore = new EmployeeAuthStatus(UUID.randomUUID(), true, Role.STAFF, false, false);

		// when / then
		assertThat(withoutStore.canAuthenticate()).isFalse();
		assertThat(withStore.canAuthenticate()).isTrue();
	}

}

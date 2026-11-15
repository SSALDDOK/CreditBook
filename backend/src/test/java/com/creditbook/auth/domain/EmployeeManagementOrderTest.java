package com.creditbook.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 직원 관리 목록 순서 (REQ-30, {@link Employee#MANAGEMENT_ORDER}): 활성 ADMIN → 활성 STAFF → 비활성(역할 무관), 묶음 안에서는 만든 순서.
 * 스프링 없이 순수 객체로 검증한다.
 */
@Tag("REQ-30")
class EmployeeManagementOrderTest {

	private static final UUID STORE_ID = UUID.fromString("00000000-0000-0000-0000-000000005001");
	private static final String HASH = "$2a$10$initialinitialinitialinitialinitialinitialinitialini";
	private static final Instant T0 = Instant.parse("2026-11-01T00:00:00Z");

	private static Instant at(int minutes) {
		return T0.plusSeconds(minutes * 60L);
	}

	/** 운영자 절차(DB 직접 처리)로 비활성이 된 이전 사장. 앱에는 ADMIN 을 비활성화하는 경로가 없다. */
	private static Employee formerOwner(Instant createdAt) {
		Employee employee = Employee.createOwner(STORE_ID, "old.owner", HASH, "전사장", createdAt);
		ReflectionTestUtils.setField(employee, "active", false);
		return employee;
	}

	private static Employee inactiveStaff(String loginId, Instant createdAt) {
		Employee employee = Employee.createStaff(STORE_ID, loginId, HASH, "퇴사직원", createdAt);
		employee.deactivate(createdAt.plusSeconds(1));
		return employee;
	}

	@ParameterizedTest(name = "[{index}] 섞는 시드 {0}")
	@ValueSource(longs = { 1L, 7L, 42L })
	@DisplayName("직원 목록은 활성 사장 → 활성 직원 → 비활성 계정(이전 사장 포함) 순서이고, 묶음 안에서는 만든 순서다")
	void management_order_groups_active_admin_active_staff_then_inactive(long seed) {
		// given — 이전 사장이 가장 먼저 만들어졌고, 비활성 직원은 활성 직원보다 먼저 만들어졌다
		Employee formerOwner = formerOwner(at(0));
		Employee retiredStaff = inactiveStaff("retired.staff", at(10));
		Employee owner = Employee.createOwner(STORE_ID, "new.owner", HASH, "새사장", at(20));
		Employee staffA = Employee.createStaff(STORE_ID, "staff.a", HASH, "직원가", at(30));
		Employee staffB = Employee.createStaff(STORE_ID, "staff.b", HASH, "직원나", at(40));
		List<Employee> employees = new ArrayList<>(List.of(formerOwner, retiredStaff, owner, staffA, staffB));
		Collections.shuffle(employees, new Random(seed));

		// when
		List<Employee> sorted = employees.stream().sorted(Employee.MANAGEMENT_ORDER).toList();

		// then
		assertThat(sorted).containsExactly(owner, staffA, staffB, formerOwner, retiredStaff);
	}

	@Test
	@DisplayName("비활성 사장은 활성 직원보다 뒤에 온다")
	void inactive_admin_comes_after_active_staff() {
		// given — 비활성 사장이 더 먼저 만들어졌어도
		Employee formerOwner = formerOwner(at(0));
		Employee staff = Employee.createStaff(STORE_ID, "staff.a", HASH, "직원가", at(30));

		// when
		List<Employee> sorted = List.of(formerOwner, staff).stream().sorted(Employee.MANAGEMENT_ORDER).toList();

		// then
		assertThat(sorted).containsExactly(staff, formerOwner);
	}

	@Test
	@DisplayName("만든 시각이 같으면 id 순서로 고정한다")
	void same_created_at_is_ordered_by_id() {
		// given
		Employee first = Employee.createStaff(STORE_ID, "staff.a", HASH, "직원가", at(30));
		Employee second = Employee.createStaff(STORE_ID, "staff.b", HASH, "직원나", at(30));
		List<Employee> expected = first.getId().toString().compareTo(second.getId().toString()) < 0
				? List.of(first, second)
				: List.of(second, first);

		// when
		List<Employee> sorted = List.of(second, first).stream().sorted(Employee.MANAGEMENT_ORDER).toList();

		// then
		assertThat(sorted).containsExactlyElementsOf(expected);
	}

}

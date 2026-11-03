package com.creditbook.auth.controller.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.creditbook.auth.application.EmployeeSummary;
import com.creditbook.auth.domain.Role;

/**
 * 직원 관리 목록 (REQ-30). 페이지 없이 전체(비활성 포함)를 ADMIN 먼저 → 활성 먼저 → 만든 순서로 싣는다.
 */
public record EmployeeListResponse(List<Item> items) {

	public EmployeeListResponse {
		items = List.copyOf(items);
	}

	public static EmployeeListResponse from(List<EmployeeSummary> employees) {
		return new EmployeeListResponse(employees.stream().map(Item::from).toList());
	}

	/**
	 * @param lastLoginAt 마지막 로그인 시각. 한 번도 로그인하지 않았으면 null
	 */
	public record Item(UUID employeeId, String name, String loginId, Role role, boolean active, Instant lastLoginAt) {

		static Item from(EmployeeSummary summary) {
			return new Item(summary.employeeId(), summary.name(), summary.loginId(), summary.role(), summary.active(),
					summary.lastLoginAt());
		}

	}

}

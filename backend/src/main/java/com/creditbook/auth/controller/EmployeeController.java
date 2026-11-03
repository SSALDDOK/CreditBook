package com.creditbook.auth.controller;

import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.creditbook.auth.application.EmployeeManagementService;
import com.creditbook.auth.controller.dto.AddEmployeeRequest;
import com.creditbook.auth.controller.dto.AddEmployeeResponse;
import com.creditbook.auth.controller.dto.EmployeeListResponse;
import com.creditbook.auth.controller.dto.ResetPasswordResponse;
import com.creditbook.global.security.CurrentEmployee;
import com.creditbook.global.security.UnauthenticatedException;

import jakarta.validation.Valid;

/**
 * 직원 계정 관리 API (REQ-30). 모든 경로가 사장(ADMIN) 전용이다 — 권한은 보안 구성이 확인한다 (STAFF 는 403).
 * 요청 형식 검증과 DTO 변환만 하고, 관리 가능 여부(본인·사장 계정 보호)는 서비스·도메인이 판단한다.
 * 처리자는 {@link CurrentEmployee} 에서만 꺼낸다. 직원 추가는 조회할 단건 경로가 없어 Location 헤더를 두지 않는다.
 */
@RestController
@RequestMapping("/api/employees")
public class EmployeeController {

	private final EmployeeManagementService employeeManagementService;
	private final ObjectProvider<CurrentEmployee> currentEmployee;

	public EmployeeController(EmployeeManagementService employeeManagementService,
			ObjectProvider<CurrentEmployee> currentEmployee) {
		this.employeeManagementService = employeeManagementService;
		this.currentEmployee = currentEmployee;
	}

	/** 직원 목록 (비활성 포함, 페이지 없음). */
	@GetMapping
	public EmployeeListResponse list() {
		return EmployeeListResponse.from(employeeManagementService.list());
	}

	/** 직원(STAFF) 추가. 201 과 함께 초기 비밀번호를 한 번만 돌려준다. 같은 아이디가 있으면 409 DUPLICATE_LOGIN_ID. */
	@PostMapping
	public ResponseEntity<AddEmployeeResponse> add(@Valid @RequestBody AddEmployeeRequest request) {
		AddEmployeeResponse body = AddEmployeeResponse.from(
				employeeManagementService.addStaff(request.name(), request.loginId(), requireCurrentEmployeeId()));
		return ResponseEntity.status(HttpStatus.CREATED).body(body);
	}

	/**
	 * 비밀번호 초기화. 요청 본문 없음. 새 초기 비밀번호를 한 번만 돌려주고, 그 직원은 다음 로그인 뒤 비밀번호를 바꿔야 한다.
	 *
	 * @param id 직원 ID. UUID 형식이 아니면 400 INVALID_INPUT, 없으면 404 EMPLOYEE_NOT_FOUND, 본인·사장 계정이면 409 ADMIN_ACCOUNT_PROTECTED
	 */
	@PostMapping("/{id}/reset-password")
	public ResetPasswordResponse resetPassword(@PathVariable("id") UUID id) {
		return ResetPasswordResponse.from(employeeManagementService.resetPassword(id, requireCurrentEmployeeId()));
	}

	/**
	 * 직원 비활성화. 요청 본문 없음. 이미 비활성이면 그대로 둔 채 같은 204 로 응답한다.
	 *
	 * @param id 직원 ID. UUID 형식이 아니면 400 INVALID_INPUT, 없으면 404 EMPLOYEE_NOT_FOUND, 본인·사장 계정이면 409 ADMIN_ACCOUNT_PROTECTED
	 */
	@PatchMapping("/{id}/deactivate")
	public ResponseEntity<Void> deactivate(@PathVariable("id") UUID id) {
		employeeManagementService.deactivate(id, requireCurrentEmployeeId());
		return ResponseEntity.noContent().build();
	}

	private UUID requireCurrentEmployeeId() {
		CurrentEmployee employee = currentEmployee.getIfAvailable();
		UUID employeeId = employee == null ? null : employee.id();
		if (employeeId == null) {
			throw new UnauthenticatedException();
		}
		return employeeId;
	}

}

package com.creditbook.customer.controller;

import java.net.URI;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.creditbook.customer.application.CustomerService;
import com.creditbook.customer.application.RegisteredCustomer;
import com.creditbook.customer.controller.dto.CustomerListResponse;
import com.creditbook.customer.controller.dto.CustomerRegisterRequest;
import com.creditbook.customer.controller.dto.CustomerResponse;
import com.creditbook.global.security.CurrentEmployee;
import com.creditbook.global.security.UnauthenticatedException;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * 고객 API. 요청 형식 검증과 DTO 변환만 하고, 등록·비활성화 규칙은 서비스·도메인이 판단한다.
 */
@RestController
@RequestMapping("/api/customers")
public class CustomerController {

	/** 목록 기본 페이지 크기. 계산대 화면 한 번에 보이는 정도. */
	static final String DEFAULT_PAGE_SIZE = "20";
	/** 한 번에 받을 수 있는 최대 페이지 크기. 큰 값으로 전체 고객을 한 번에 긁어 가지 못하게 한다. */
	static final int MAX_PAGE_SIZE = 100;
	/** 검색어 최대 길이 — 이름(20자)과 하이픈 넣은 연락처(13자)를 모두 담는 길이. */
	static final int MAX_QUERY_LENGTH = 20;

	private final CustomerService customerService;
	private final ObjectProvider<CurrentEmployee> currentEmployee;

	public CustomerController(CustomerService customerService, ObjectProvider<CurrentEmployee> currentEmployee) {
		this.customerService = customerService;
		this.currentEmployee = currentEmployee;
	}

	/** 고객 등록 (REQ-1). 잔액 0원인 선결제 계좌가 함께 생성된다. */
	@PostMapping
	public ResponseEntity<CustomerResponse> register(@Valid @RequestBody CustomerRegisterRequest request) {
		RegisteredCustomer registered = customerService.register(request.name(), request.phoneDigits());
		URI location = ServletUriComponentsBuilder.fromCurrentRequest()
				.path("/{id}")
				.buildAndExpand(registered.customerId())
				.toUri();
		return ResponseEntity.created(location).body(CustomerResponse.from(registered));
	}

	/**
	 * 고객 목록·검색 (REQ-2). 활성 고객만, 잔액과 함께, 최근 등록순으로 돌려준다. 연락처는 가려서 내보낸다.
	 *
	 * @param q 검색어 (선택). 숫자만(하이픈·공백 허용)이면 연락처 뒷자리, 그 밖은 이름 일부. 없거나 공백이면 전체
	 * @param page 0부터 시작하는 페이지 번호 (기본 0)
	 * @param size 페이지 크기 (기본 20, 최대 100)
	 */
	@GetMapping
	public CustomerListResponse search(
			@RequestParam(name = "q", required = false)
			@Size(max = MAX_QUERY_LENGTH, message = "검색어는 {max}자 이하여야 합니다.") String q,
			@RequestParam(name = "page", defaultValue = "0")
			@Min(value = 0, message = "페이지 번호는 0 이상이어야 합니다.") int page,
			@RequestParam(name = "size", defaultValue = DEFAULT_PAGE_SIZE)
			@Min(value = 1, message = "페이지 크기는 {value} 이상이어야 합니다.")
			@Max(value = MAX_PAGE_SIZE, message = "페이지 크기는 {value} 이하여야 합니다.") int size) {
		return CustomerListResponse.from(customerService.search(q, page, size));
	}

	/**
	 * 고객 비활성화 (REQ-4). 요청 본문 없음. ADMIN 만 부를 수 있다 — 권한은 보안 구성이 확인한다 (REQ-16, STAFF 는 403).
	 * 잔액 0원 고객만 비활성화되고, 이미 비활성이면 그대로 둔 채 같은 204 로 응답한다.
	 *
	 * @param id 고객 ID. UUID 형식이 아니면 400 INVALID_INPUT
	 */
	@PatchMapping("/{id}/deactivate")
	public ResponseEntity<Void> deactivate(@PathVariable("id") UUID id) {
		customerService.deactivate(id, requireCurrentEmployeeId());
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

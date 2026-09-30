package com.creditbook.customer.controller;

import java.net.URI;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.creditbook.customer.application.CustomerService;
import com.creditbook.customer.application.RegisteredCustomer;
import com.creditbook.customer.controller.dto.CustomerRegisterRequest;
import com.creditbook.customer.controller.dto.CustomerResponse;

import jakarta.validation.Valid;

/**
 * 고객 API. 요청 형식 검증과 DTO 변환만 하고, 등록 규칙은 서비스·도메인이 판단한다.
 */
@RestController
@RequestMapping("/api/customers")
public class CustomerController {

	private final CustomerService customerService;

	public CustomerController(CustomerService customerService) {
		this.customerService = customerService;
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

}

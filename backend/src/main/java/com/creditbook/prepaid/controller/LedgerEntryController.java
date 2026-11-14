package com.creditbook.prepaid.controller;

import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.creditbook.global.security.CurrentEmployee;
import com.creditbook.global.security.UnauthenticatedException;
import com.creditbook.prepaid.application.LedgerHistoryService;
import com.creditbook.prepaid.controller.dto.LedgerEntryListResponse;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 거래 이력 API. 요청 형식 검증과 DTO 변환만 하고, 취소 가능 판단은 도메인이 한다.
 * 조회만 있다 — 거래 수정·삭제 경로는 만들지 않는다 (REQ-14).
 */
@RestController
public class LedgerEntryController {

	/** 기본 페이지 크기 (고객 목록과 같다). */
	static final String DEFAULT_PAGE_SIZE = "20";
	/** 한 번에 받을 수 있는 최대 페이지 크기. */
	static final int MAX_PAGE_SIZE = 100;

	private final LedgerHistoryService ledgerHistoryService;
	private final ObjectProvider<CurrentEmployee> currentEmployee;

	public LedgerEntryController(LedgerHistoryService ledgerHistoryService,
			ObjectProvider<CurrentEmployee> currentEmployee) {
		this.ledgerHistoryService = ledgerHistoryService;
		this.currentEmployee = currentEmployee;
	}

	/**
	 * 고객의 거래 이력을 최신순으로 돌려준다. 각 거래에 지금 취소할 수 있는지(cancellable)를 함께 담는다.
	 * 비활성 고객의 이력도 조회된다 (REQ-4). 로그인한 직원(ADMIN·STAFF) 모두 부를 수 있다.
	 *
	 * @param customerId 고객 ID. UUID 형식이 아니면 400 INVALID_INPUT, 없거나 다른 매장 고객이면 404 CUSTOMER_NOT_FOUND (REQ-36)
	 * @param page 0부터 시작하는 페이지 번호 (기본 0)
	 * @param size 페이지 크기 (기본 20, 최대 100)
	 */
	@GetMapping("/api/customers/{customerId}/transactions")
	public LedgerEntryListResponse getHistory(
			@PathVariable("customerId") UUID customerId,
			@RequestParam(name = "page", defaultValue = "0")
			@Min(value = 0, message = "페이지 번호는 0 이상이어야 합니다.") int page,
			@RequestParam(name = "size", defaultValue = DEFAULT_PAGE_SIZE)
			@Min(value = 1, message = "페이지 크기는 {value} 이상이어야 합니다.")
			@Max(value = MAX_PAGE_SIZE, message = "페이지 크기는 {value} 이하여야 합니다.") int size) {
		return LedgerEntryListResponse.from(ledgerHistoryService.getHistory(requireCurrentStoreId(), customerId, page,
				size));
	}

	/** 현재 직원의 매장. 알 수 없으면 401 — 매장 조건 없이 처리하지 않는다 (REQ-36). */
	private UUID requireCurrentStoreId() {
		CurrentEmployee employee = currentEmployee.getIfAvailable();
		UUID storeId = employee == null ? null : employee.storeId();
		if (storeId == null) {
			throw new UnauthenticatedException();
		}
		return storeId;
	}

}

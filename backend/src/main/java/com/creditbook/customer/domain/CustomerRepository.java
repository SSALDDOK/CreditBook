package com.creditbook.customer.domain;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * 고객 저장소. 구현은 infrastructure 에 있다.
 * 고객은 삭제하지 않는다 (비활성화만 — REQ-4). 그래서 삭제 메서드를 두지 않는다.
 */
public interface CustomerRepository {

	/** 새 고객을 추가한다 (INSERT). 이미 저장된 고객을 다시 넘기면 안 된다. */
	void add(Customer customer);

	Optional<Customer> findById(UUID id);

	/**
	 * 활성 고객을 잔액과 함께 검색한다 (REQ-2). 비활성 고객은 목록에서 빠진다 (REQ-4).
	 * 정렬은 최근 등록순(등록 시각 내림차순, 같으면 id 내림차순)으로 고정이며 {@code pageable} 의 정렬은 쓰지 않는다.
	 */
	Page<CustomerSummary> searchActive(CustomerSearchKeyword keyword, Pageable pageable);

}

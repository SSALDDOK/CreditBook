package com.creditbook.customer.domain;

import java.util.Optional;
import java.util.UUID;

/**
 * 고객 저장소. 구현은 infrastructure 에 있다.
 * 고객은 삭제하지 않는다 (비활성화만 — REQ-4). 그래서 삭제 메서드를 두지 않는다.
 */
public interface CustomerRepository {

	/** 새 고객을 추가한다 (INSERT). 이미 저장된 고객을 다시 넘기면 안 된다. */
	void add(Customer customer);

	Optional<Customer> findById(UUID id);

}

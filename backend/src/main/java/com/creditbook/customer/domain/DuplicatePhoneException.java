package com.creditbook.customer.domain;

import java.util.List;
import java.util.Objects;

/**
 * 같은 연락처의 고객이 이미 있는데 진행 확인 없이 등록을 요청함 (REQ-1). 고객·계좌는 만들지 않는다.
 * 같은 번호 등록 자체는 허용된다 — 직원이 기존 고객을 확인한 뒤 확인 표시를 붙여 다시 요청하면 등록된다.
 */
public class DuplicatePhoneException extends CustomerDomainException {

	private final List<SamePhoneCustomer> existingCustomers;

	/**
	 * @param existingCustomers 같은 번호의 기존 고객 전부 (1건 이상, 활성 먼저 → 최근 등록순)
	 */
	public DuplicatePhoneException(List<SamePhoneCustomer> existingCustomers) {
		super("같은 번호의 고객이 있습니다.");
		Objects.requireNonNull(existingCustomers, "existingCustomers");
		if (existingCustomers.isEmpty()) {
			throw new IllegalArgumentException("existingCustomers 는 1건 이상이어야 한다");
		}
		this.existingCustomers = List.copyOf(existingCustomers);
	}

	public List<SamePhoneCustomer> getExistingCustomers() {
		return existingCustomers;
	}

}

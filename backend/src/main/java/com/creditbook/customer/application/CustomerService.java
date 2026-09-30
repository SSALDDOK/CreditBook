package com.creditbook.customer.application;

import java.time.Clock;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.creditbook.customer.domain.Customer;
import com.creditbook.customer.domain.CustomerRepository;
import com.creditbook.prepaid.domain.PrepaidAccount;
import com.creditbook.prepaid.domain.PrepaidAccountRepository;

/**
 * 고객 유스케이스. 트랜잭션 경계만 잡고, 규칙은 Customer·PrepaidAccount 가 판단한다.
 */
@Service
public class CustomerService {

	private static final Logger log = LoggerFactory.getLogger(CustomerService.class);

	private final CustomerRepository customerRepository;
	private final PrepaidAccountRepository prepaidAccountRepository;
	private final Clock clock;

	public CustomerService(CustomerRepository customerRepository, PrepaidAccountRepository prepaidAccountRepository,
			Clock clock) {
		this.customerRepository = customerRepository;
		this.prepaidAccountRepository = prepaidAccountRepository;
		this.clock = clock;
	}

	/**
	 * 고객을 등록하고 잔액 0원인 선결제 계좌를 함께 연다 (REQ-1). 둘 다 저장되거나 둘 다 저장되지 않는다.
	 *
	 * @param phone 숫자만 남긴 정규형 (필수)
	 */
	@Transactional
	public RegisteredCustomer register(String name, String phone) {
		Instant now = clock.instant();
		Customer customer = Customer.register(name, phone, now);
		customerRepository.add(customer);
		PrepaidAccount account = PrepaidAccount.open(customer.getId(), now);
		prepaidAccountRepository.add(account);

		// 고객 이름·연락처는 로그에 남기지 않는다 (운영 점검 가이드 §6)
		log.info("customer registered: customerId={}, accountId={}", customer.getId(), account.getId());
		return new RegisteredCustomer(customer.getId(), customer.getName(), customer.getPhone(), account.getId(),
				account.getBalance(), customer.getCreatedAt());
	}

}

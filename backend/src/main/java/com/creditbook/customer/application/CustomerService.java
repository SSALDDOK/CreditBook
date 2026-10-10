package com.creditbook.customer.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.creditbook.customer.domain.Customer;
import com.creditbook.customer.domain.CustomerBalanceNotZeroException;
import com.creditbook.customer.domain.CustomerNotFoundException;
import com.creditbook.customer.domain.CustomerRepository;
import com.creditbook.customer.domain.CustomerSearchKeyword;
import com.creditbook.customer.domain.CustomerSummary;
import com.creditbook.prepaid.domain.PrepaidAccount;
import com.creditbook.prepaid.domain.PrepaidAccountNotFoundException;
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

	/**
	 * 활성 고객을 잔액과 함께 검색한다 (REQ-2). 검색어가 비면 전체, 숫자만이면 연락처 뒷자리, 그 밖은 이름 부분 일치.
	 * 비활성 고객은 목록에서 빠진다 (REQ-4). 정렬은 최근 등록순 고정.
	 *
	 * @param query 검색어 (null 가능). 개인정보일 수 있으므로 로그에 남기지 않는다
	 * @param page 0부터 시작하는 페이지 번호 (형식 검증은 controller 가 한다)
	 * @param size 페이지 크기
	 */
	@Transactional(readOnly = true)
	public Page<CustomerSummary> search(String query, int page, int size) {
		CustomerSearchKeyword keyword = CustomerSearchKeyword.parse(query);
		Page<CustomerSummary> result = customerRepository.searchActive(keyword, PageRequest.of(page, size));
		// 검색어 값은 남기지 않고 해석 유형만 남긴다
		log.debug("customer search: type={}, page={}, size={}, total={}", keyword.type(), page, size,
				result.getTotalElements());
		return result;
	}

	/**
	 * 잔액이 0원인 고객을 비활성화한다 (REQ-4). 권한(ADMIN만 — REQ-16)은 보안 구성이 요청 단계에서 확인한다.
	 * 잔액 판단은 {@link PrepaidAccount}, 상태 변경은 {@link Customer} 가 한다. 이미 비활성이면 아무것도 바꾸지 않는다.
	 * <p>
	 * 계좌는 version 을 올리는 조회로 읽는다 — 잔액 0원을 확인한 뒤 같은 계좌에 충전이 먼저 커밋되면 이 트랜잭션이 409 로 실패하고,
	 * 이쪽이 먼저 커밋되면 늦은 충전이 실패한다. 잔액이 남은 고객이 비활성 상태로 커밋되는 일이 없다.
	 *
	 * @param customerId 비활성화할 고객
	 * @param performedBy 요청한 직원 ID — 인증 정보에서 꺼낸 값만 넘긴다 (로그용)
	 * @throws CustomerNotFoundException 고객이 없을 때
	 * @throws PrepaidAccountNotFoundException 고객의 계좌가 없을 때 (등록과 함께 생기므로 사실상 일어나지 않는다)
	 * @throws CustomerBalanceNotZeroException 잔액이 0원이 아닐 때. 아무것도 바꾸지 않는다
	 */
	@Transactional
	public void deactivate(UUID customerId, UUID performedBy) {
		Objects.requireNonNull(customerId, "customerId");
		Objects.requireNonNull(performedBy, "performedBy");
		Customer customer = customerRepository.findById(customerId)
				.orElseThrow(() -> new CustomerNotFoundException(customerId));
		PrepaidAccount account = prepaidAccountRepository.findByCustomerIdAndIncrementVersion(customerId)
				.orElseThrow(() -> new PrepaidAccountNotFoundException(customerId));

		boolean wasActive = customer.isActive();
		try {
			customer.deactivate(account, clock.instant());
		}
		catch (CustomerBalanceNotZeroException ex) {
			// 정상적인 거절이므로 WARN (운영 점검 가이드 §6). 고객 이름·연락처는 남기지 않는다
			log.warn("customer deactivation rejected: balance not zero, customerId={}, accountId={}, balance={}, performedBy={}",
					customerId, account.getId(), ex.getBalance().toPlainString(), performedBy);
			throw ex;
		}

		if (wasActive) {
			log.info("customer deactivated: customerId={}, performedBy={}", customerId, performedBy);
		}
		else {
			log.info("customer already inactive: customerId={}, performedBy={}", customerId, performedBy);
		}
	}

}

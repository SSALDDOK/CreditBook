package com.creditbook.customer.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
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
import com.creditbook.customer.domain.CustomerDetail;
import com.creditbook.customer.domain.CustomerNotFoundException;
import com.creditbook.customer.domain.CustomerRepository;
import com.creditbook.customer.domain.CustomerSearchKeyword;
import com.creditbook.customer.domain.CustomerSummary;
import com.creditbook.customer.domain.DuplicatePhoneException;
import com.creditbook.customer.domain.SamePhoneCustomer;
import com.creditbook.prepaid.domain.PrepaidAccount;
import com.creditbook.prepaid.domain.PrepaidAccountNotFoundException;
import com.creditbook.prepaid.domain.PrepaidAccountRepository;

/**
 * 고객 유스케이스. 트랜잭션 경계만 잡고, 규칙은 Customer·PrepaidAccount 가 판단한다.
 */
@Service
public class CustomerService {

	private static final Logger log = LoggerFactory.getLogger(CustomerService.class);

	/** 고객 상세에 담는 최근 거래 최대 건수 (REQ-11). 전체 이력은 거래 이력 조회가 맡는다. */
	public static final int RECENT_ENTRY_LIMIT = 5;

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
	 * 메모 없이 고객을 등록한다. 같은 번호 확인을 하지 않는다 — {@code register(name, phone, null, true)} 와 같다.
	 * 등록 API 는 {@link #register(String, String, String, boolean)} 을 쓴다.
	 *
	 * @param phone 숫자만 남긴 정규형 (필수)
	 */
	@Transactional
	public RegisteredCustomer register(String name, String phone) {
		return register(name, phone, null, true);
	}

	/**
	 * 고객을 등록하고 잔액 0원인 선결제 계좌를 함께 연다 (REQ-1). 둘 다 저장되거나 둘 다 저장되지 않는다.
	 * <p>
	 * 같은 연락처의 고객(비활성 포함)이 있으면 {@code confirmDuplicatePhone} 이 false 일 때 아무것도 저장하지 않고 거절한다 —
	 * 직원이 기존 고객 이름을 보고 같은 사람인지 판단한 뒤 true 로 다시 요청하면 등록된다. 같은 번호 등록 자체는 허용하므로
	 * 동시에 같은 번호로 등록되는 경합은 막지 않는다. 이름·연락처·메모 검증은 같은 번호 확인보다 먼저 한다.
	 *
	 * @param phone 숫자만 남긴 정규형 (필수)
	 * @param memo 메모 (선택, null 가능). 정규화·길이 검증은 {@link Customer} 가 한다
	 * @param confirmDuplicatePhone 같은 번호 고객이 있어도 등록할지 여부
	 * @throws DuplicatePhoneException 같은 번호 고객이 있고 확인 표시가 없을 때. 고객·계좌를 저장하지 않는다
	 */
	@Transactional
	public RegisteredCustomer register(String name, String phone, String memo, boolean confirmDuplicatePhone) {
		Instant now = clock.instant();
		Customer customer = Customer.register(name, phone, memo, now);
		if (!confirmDuplicatePhone) {
			List<SamePhoneCustomer> samePhone = customerRepository.findAllByPhone(customer.getPhone());
			if (!samePhone.isEmpty()) {
				// 정상 흐름(직원 확인 요청)이라 INFO. 고객 이름·연락처는 남기지 않는다 (운영 점검 가이드 §6)
				log.info("customer registration held: duplicate phone, existingCount={}, existingCustomerIds={}",
						samePhone.size(), samePhone.stream().map(SamePhoneCustomer::id).toList());
				throw new DuplicatePhoneException(samePhone);
			}
		}
		customerRepository.add(customer);
		PrepaidAccount account = PrepaidAccount.open(customer.getId(), now);
		prepaidAccountRepository.add(account);

		// 고객 이름·연락처는 로그에 남기지 않는다 (운영 점검 가이드 §6)
		log.info("customer registered: customerId={}, accountId={}", customer.getId(), account.getId());
		return new RegisteredCustomer(customer.getId(), customer.getName(), customer.getPhone(), account.getId(),
				account.getBalance(), customer.getMemo(), customer.getCreatedAt());
	}

	/**
	 * 고객을 잔액과 함께 검색한다 (REQ-2). 검색어가 비면 전체, 숫자만이면 연락처 뒷자리, 그 밖은 이름 부분 일치.
	 * 기본은 활성 고객만이고 비활성 고객은 목록·검색·건수에서 빠진다 (REQ-4). {@code includeInactive} 가 true 면
	 * 비활성 고객도 함께 담는다 (REQ-33). 정렬은 최근 등록순 고정.
	 *
	 * @param query 검색어 (null 가능). 개인정보일 수 있으므로 로그에 남기지 않는다
	 * @param includeInactive 비활성 고객 포함 여부
	 * @param page 0부터 시작하는 페이지 번호 (형식 검증은 controller 가 한다)
	 * @param size 페이지 크기
	 */
	@Transactional(readOnly = true)
	public Page<CustomerSummary> search(String query, boolean includeInactive, int page, int size) {
		CustomerSearchKeyword keyword = CustomerSearchKeyword.parse(query);
		Page<CustomerSummary> result = customerRepository.search(keyword, includeInactive, PageRequest.of(page, size));
		// 검색어 값은 남기지 않고 해석 유형만 남긴다
		log.debug("customer search: type={}, includeInactive={}, page={}, size={}, total={}", keyword.type(),
				includeInactive, page, size, result.getTotalElements());
		return result;
	}

	/**
	 * 고객 상세를 잔액·최근 거래와 함께 돌려준다 (REQ-11). 비활성 고객도 조회된다 (REQ-4).
	 * 잔액과 최근 거래는 저장소가 한 SQL 문장으로 읽으므로 같은 시점의 값이다 — 여기서 잔액을 계산하지 않는다.
	 *
	 * @throws CustomerNotFoundException 고객이 없을 때
	 */
	@Transactional(readOnly = true)
	public CustomerDetail getDetail(UUID customerId) {
		Objects.requireNonNull(customerId, "customerId");
		CustomerDetail detail = customerRepository.findDetail(customerId, RECENT_ENTRY_LIMIT)
				.orElseThrow(() -> new CustomerNotFoundException(customerId));
		// 고객 이름·연락처는 로그에 남기지 않는다 (운영 점검 가이드 §6)
		log.debug("customer detail: customerId={}, recentEntries={}", customerId, detail.recentEntries().size());
		return detail;
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

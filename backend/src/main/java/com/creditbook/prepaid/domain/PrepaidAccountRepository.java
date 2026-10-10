package com.creditbook.prepaid.domain;

import java.util.Optional;
import java.util.UUID;

/**
 * 선결제 계좌 저장소. 구현은 infrastructure 에 있다.
 * 잔액은 이 저장소로 직접 바꾸지 않는다 — PrepaidAccount 의 메서드로 바꾸고 트랜잭션 커밋 때 반영된다.
 * 계좌는 삭제하지 않는다. 그래서 삭제 메서드를 두지 않는다.
 */
public interface PrepaidAccountRepository {

	/** 새 계좌를 추가한다 (INSERT). 이미 저장된 계좌를 다시 넘기면 안 된다. */
	void add(PrepaidAccount account);

	Optional<PrepaidAccount> findByCustomerId(UUID customerId);

	/**
	 * 고객의 계좌를 조회하고, 이 트랜잭션이 커밋될 때 잔액을 바꾸지 않더라도 계좌 version 을 올린다.
	 * <p>
	 * 잔액을 읽고 그 값으로 다른 상태를 바꾸는 유스케이스(예: 잔액 0원 고객 비활성화 — REQ-4)가 쓴다. 조회 뒤 다른 트랜잭션이
	 * 잔액을 바꿔 먼저 커밋하면 이 트랜잭션의 커밋이 Optimistic Lock(낙관적 락) 충돌로 실패하고, 반대로 이쪽이 먼저 커밋하면
	 * 늦게 커밋하는 충전·사용이 실패한다. 어느 쪽이든 "읽은 잔액" 과 "커밋 시점의 잔액" 이 어긋난 채 커밋되지 않는다.
	 */
	Optional<PrepaidAccount> findByCustomerIdAndIncrementVersion(UUID customerId);

}

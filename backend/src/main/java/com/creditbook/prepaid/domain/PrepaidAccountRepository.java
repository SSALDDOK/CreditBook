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

}

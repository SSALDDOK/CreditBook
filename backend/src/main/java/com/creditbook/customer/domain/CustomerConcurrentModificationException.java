package com.creditbook.customer.domain;

import java.util.UUID;

/**
 * 고객 정보 수정 요청이 낡은 버전을 보냄 (REQ-3) — 화면이 고객 정보를 읽은 뒤 다른 요청이 먼저 고객을 바꿨다.
 * 값이 같아도 거절한다. 전역 예외 처리기가 409 CONCURRENT_MODIFICATION 으로 바꾼다.
 */
public class CustomerConcurrentModificationException extends CustomerDomainException {

	private final UUID customerId;

	private final long expectedVersion;

	private final long currentVersion;

	public CustomerConcurrentModificationException(UUID customerId, long expectedVersion, long currentVersion) {
		super("다른 요청이 먼저 고객 정보를 바꿨습니다.");
		this.customerId = customerId;
		this.expectedVersion = expectedVersion;
		this.currentVersion = currentVersion;
	}

	public UUID getCustomerId() {
		return customerId;
	}

	public long getExpectedVersion() {
		return expectedVersion;
	}

	public long getCurrentVersion() {
		return currentVersion;
	}

}

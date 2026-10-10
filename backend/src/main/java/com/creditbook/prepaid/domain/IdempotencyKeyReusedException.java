package com.creditbook.prepaid.domain;

import java.util.UUID;

/**
 * 이미 쓰인 요청 키로 다른 거래(고객·유형·금액·메모 중 하나라도 다름)를 요청했을 때 (REQ-10).
 * 처음 거래는 그대로 두고 이번 요청은 처리하지 않는다.
 */
public class IdempotencyKeyReusedException extends PrepaidDomainException {

	private final UUID storedEntryId;

	public IdempotencyKeyReusedException(UUID storedEntryId) {
		super("같은 요청 키로 다른 거래를 요청했습니다. 새로 시도해 주세요.");
		this.storedEntryId = storedEntryId;
	}

	/** 이 키로 먼저 만들어진 거래의 ID (로그용 — 응답에는 싣지 않는다). */
	public UUID getStoredEntryId() {
		return storedEntryId;
	}

}

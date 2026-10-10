package com.creditbook.prepaid.domain;

/**
 * 같은 요청 키의 거래를 저장하려 했는데 다른 요청이 먼저 저장해 ux_ledger_entries_idem 에 걸렸을 때.
 * <p>
 * 저장소 구현이 이 제약 위반만 이 예외로 바꾼다 — 다른 무결성 위반은 그대로 올라간다. 호출자는 먼저 저장된 거래를 다시
 * 찾아 재응답하고, 그래도 찾지 못하면(드묾) 동시 요청 충돌로 응답한다.
 */
public class IdempotencyKeyConflictException extends PrepaidDomainException {

	public IdempotencyKeyConflictException(Throwable cause) {
		super("같은 요청 키의 거래가 먼저 저장되었습니다.");
		initCause(cause);
	}

}

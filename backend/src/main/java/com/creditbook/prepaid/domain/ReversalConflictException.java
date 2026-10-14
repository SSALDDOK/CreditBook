package com.creditbook.prepaid.domain;

/**
 * 반제 거래를 저장하려 했는데 같은 원본을 다른 요청이 먼저 반제해 ux_ledger_entries_reverses 에 걸렸을 때.
 * <p>
 * 저장소 구현이 이 제약 위반만 이 예외로 바꾼다. 호출자는 새 트랜잭션에서 한 번 더 판정해, 먼저 커밋된 반제를 본 도메인이
 * {@link AlreadyCancelledException} 으로 거절하게 한다. 그래도 같은 위반이 나면(드묾) 동시 요청 충돌로 응답한다.
 */
public class ReversalConflictException extends PrepaidDomainException {

	public ReversalConflictException(Throwable cause) {
		super("같은 거래의 취소가 먼저 저장되었습니다.");
		initCause(cause);
	}

}

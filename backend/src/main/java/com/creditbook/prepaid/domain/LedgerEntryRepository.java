package com.creditbook.prepaid.domain;

import java.util.Optional;

/**
 * 원장 거래 저장소. 구현은 infrastructure 에 있다.
 * <p>
 * 원장은 추가만 한다 (append-only). 수정·삭제 메서드를 두지 않는다 — 정정은 반제 거래를 새로 추가한다.
 * DB 트리거(trg_ledger_entries_no_update_delete)가 최후 방어선이다.
 */
public interface LedgerEntryRepository {

	/**
	 * 새 거래를 추가한다 (INSERT). 거래는 {@link PrepaidAccount} 의 메서드가 만든 것만 넘긴다.
	 * 제약 위반이 커밋 때가 아니라 이 호출에서 드러나도록 바로 DB 에 반영한다.
	 *
	 * @throws IdempotencyKeyConflictException 같은 요청 키의 거래가 이미 저장돼 ux_ledger_entries_idem 에 걸렸을 때.
	 *         다른 제약 위반은 이 예외로 바꾸지 않는다
	 */
	void add(LedgerEntry entry);

	/** 요청 키로 저장된 거래를 찾는다 (키는 전역에서 유일하다). */
	Optional<LedgerEntry> findByIdempotencyKey(IdempotencyKey key);

}

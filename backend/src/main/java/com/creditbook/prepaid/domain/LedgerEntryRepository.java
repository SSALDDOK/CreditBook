package com.creditbook.prepaid.domain;

/**
 * 원장 거래 저장소. 구현은 infrastructure 에 있다.
 * <p>
 * 원장은 추가만 한다 (append-only). 수정·삭제 메서드를 두지 않는다 — 정정은 반제 거래를 새로 추가한다.
 * DB 트리거(trg_ledger_entries_no_update_delete)가 최후 방어선이다.
 */
public interface LedgerEntryRepository {

	/** 새 거래를 추가한다 (INSERT). 거래는 {@link PrepaidAccount} 의 메서드가 만든 것만 넘긴다. */
	void add(LedgerEntry entry);

}

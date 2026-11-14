package com.creditbook.prepaid.domain;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

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
	 * @throws ReversalConflictException 같은 원본의 반제 거래가 이미 저장돼 ux_ledger_entries_reverses 에 걸렸을 때.
	 *         다른 제약 위반은 이 두 예외로 바꾸지 않는다
	 */
	void add(LedgerEntry entry);

	/**
	 * 요청 키로 저장된 거래를 찾는다 (키는 전역에서 유일하다 — ux_ledger_entries_idem). 매장 조건이 없으므로, 호출자는 먼저 자기 매장의
	 * 계좌를 찾은 뒤 이 결과를 그 계좌와 비교해야 한다 (다른 매장 거래는 '다른 요청' 으로 거절된다).
	 */
	Optional<LedgerEntry> findByIdempotencyKey(IdempotencyKey key);

	/** 그 매장의 거래를 ID 로 찾는다. 다른 매장 거래면 빈 값이다 (REQ-36). */
	Optional<LedgerEntry> findByIdAndStoreId(UUID id, UUID storeId);

	/**
	 * {@code reverses_id = originalId} 인 반제 거래를 찾는다. ux_ledger_entries_reverses 때문에 많아야 1건이다.
	 *
	 * @param originalId 반제 대상이 된 원 거래의 ID
	 */
	Optional<LedgerEntry> findByReversesId(UUID originalId);

	/**
	 * 고객의 거래 이력을 최신순(seq 내림차순)으로 한 페이지 읽는다 (거래 이력 조회). 읽기만 한다.
	 * 각 거래에 처리 직원 이름과 취소 여부(이 거래를 반제한 행이 있는가)를 붙인다. 고객의 활성 여부는 보지 않는다.
	 * 계좌나 거래가 없는 고객이면 빈 페이지다 — 고객이 있는지는 호출자가 따로 확인한다. 다른 매장 고객이면 빈 페이지다 (REQ-36).
	 *
	 * @param storeId 요청한 직원의 매장
	 * @param customerId 고객 ID
	 * @param pageable 페이지 번호·크기 (정렬은 seq 내림차순 고정이라 넘긴 정렬은 쓰지 않는다)
	 */
	Page<LedgerHistoryEntry> findHistoryByCustomerId(UUID storeId, UUID customerId, Pageable pageable);

}

package com.creditbook.store.domain;

import java.util.Optional;
import java.util.UUID;

/**
 * 매장 저장소. 구현은 infrastructure 에 있다. 매장은 지우지 않는다 — 삭제 메서드를 두지 않는다.
 */
public interface StoreRepository {

	/** 매장 행을 추가하고 바로 DB 에 보낸다 (INSERT + flush) — 제약 위반이 커밋 시점이 아니라 호출 안에서 드러나게 한다. */
	void add(Store store);

	/**
	 * 매장 조회. id 는 로그인한 직원의 매장(employees.store_id)만 넘긴다 — 다른 매장 조회에 쓰지 않는다.
	 * 직원의 store_id 는 외래 키라 그 매장은 항상 있다.
	 */
	Optional<Store> findById(UUID id);

}

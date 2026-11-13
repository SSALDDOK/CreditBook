package com.creditbook.store.domain;

/**
 * 매장 저장소. 구현은 infrastructure 에 있다. 매장은 지우지 않는다 — 삭제 메서드를 두지 않는다.
 */
public interface StoreRepository {

	/** 매장 행을 추가하고 바로 DB 에 보낸다 (INSERT + flush) — 제약 위반이 커밋 시점이 아니라 호출 안에서 드러나게 한다. */
	void add(Store store);

}

package com.creditbook.store.domain;

/**
 * 매장 정보 저장소. 구현은 infrastructure 에 있다. 매장은 한 행뿐이다 (ux_store_profile_singleton).
 */
public interface StoreProfileRepository {

	/**
	 * 매장 행을 추가하고 바로 DB 에 보낸다 (INSERT + flush) — 제약 위반이 커밋 시점이 아니라 호출 안에서 드러나게 한다.
	 *
	 * @throws SetupAlreadyCompletedException 매장 행이 이미 있어 ux_store_profile_singleton 에 걸렸을 때
	 */
	void add(StoreProfile storeProfile);

}

package com.creditbook.customer.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 고객 변경 기록 (customer_change_logs, REQ-3). 누가·언제·어느 칸을 바꿨는지만 남기고 값은 남기지 않는다.
 * 추가만 한다 — 고치거나 지우는 메서드를 두지 않는다 (DB 트리거도 막는다). 구현은 infrastructure 에 있다.
 */
public interface CustomerChangeLogRepository {

	/**
	 * 변경 기록 1행을 추가한다. 호출하는 쪽의 트랜잭션 안에서 실행돼야 한다.
	 *
	 * @param storeId 고객의 매장 (처리 직원도 같은 매장이어야 한다 — 복합 외래 키)
	 * @param changedBy 처리 직원
	 * @param changedAt 변경 시각 (고객 updated_at 과 같은 값)
	 * @param changes 바뀐 칸 — 하나 이상이어야 한다
	 * @throws IllegalArgumentException 바뀐 칸이 없을 때 (호출자 버그)
	 */
	void add(UUID storeId, UUID customerId, UUID changedBy, Instant changedAt, CustomerChanges changes);

}

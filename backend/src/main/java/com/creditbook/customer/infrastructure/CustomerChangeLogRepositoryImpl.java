package com.creditbook.customer.infrastructure;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Repository;

import com.creditbook.customer.domain.CustomerChangeLogRepository;
import com.creditbook.customer.domain.CustomerChanges;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * {@link CustomerChangeLogRepository} 구현. 변경 기록은 추가만 하고 읽는 화면·API 가 없어 엔티티로 만들지 않고
 * 네이티브 INSERT 한 문장으로 넣는다. 값은 모두 파라미터로 바인딩한다. 호출하는 쪽의 트랜잭션 안에서 실행돼야 한다.
 * <p>
 * UPDATE·DELETE 문장을 두지 않는다 — DB 트리거(trg_customer_change_logs_no_update_delete·no_truncate)도 막는다.
 */
@Repository
class CustomerChangeLogRepositoryImpl implements CustomerChangeLogRepository {

	@PersistenceContext
	private EntityManager em;

	@Override
	public void add(UUID storeId, UUID customerId, UUID changedBy, Instant changedAt, CustomerChanges changes) {
		if (!changes.hasAny()) {
			// ck_customer_change_logs_any_field 보다 먼저 — 바뀐 칸 없는 기록은 호출자 버그다
			throw new IllegalArgumentException("changes must have at least one changed field");
		}
		em.createNativeQuery("""
				INSERT INTO customer_change_logs
				    (store_id, customer_id, changed_by, changed_at, name_changed, phone_changed, memo_changed)
				VALUES (:storeId, :customerId, :changedBy, :changedAt, :nameChanged, :phoneChanged, :memoChanged)
				""")
				.setParameter("storeId", storeId)
				.setParameter("customerId", customerId)
				.setParameter("changedBy", changedBy)
				.setParameter("changedAt", changedAt)
				.setParameter("nameChanged", changes.nameChanged())
				.setParameter("phoneChanged", changes.phoneChanged())
				.setParameter("memoChanged", changes.memoChanged())
				.executeUpdate();
	}

}

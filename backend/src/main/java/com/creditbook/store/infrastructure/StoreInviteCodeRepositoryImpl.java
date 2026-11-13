package com.creditbook.store.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;

import com.creditbook.store.domain.StoreInviteCodeRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * {@link StoreInviteCodeRepository} 의 JPA 구현. 사용 횟수 차감은 엔티티를 읽어 고쳐 쓰지 않고 조건부 UPDATE 한 문장으로 한다 (V5 주석) —
 * 읽은 뒤 쓰는 사이에 다른 요청이 끼어 한도를 넘기지 않게 한다. 마지막 방어선은 ck_store_invite_codes_used_count 다.
 */
@Repository
class StoreInviteCodeRepositoryImpl implements StoreInviteCodeRepository {

	static final String CLAIM_USE_SQL = """
			UPDATE store_invite_codes SET used_count = used_count + 1
			 WHERE code_hash = :codeHash AND active AND used_count < max_uses
			RETURNING id
			""";

	@PersistenceContext
	private EntityManager em;

	/** RETURNING 결과를 읽으려고 결과 타입을 주어 조회로 실행한다. code_hash 는 유니크라 0행 또는 1행이다. */
	@Override
	public Optional<UUID> claimUse(String codeHash) {
		List<?> ids = em.createNativeQuery(CLAIM_USE_SQL, UUID.class)
				.setParameter("codeHash", codeHash)
				.getResultList();
		return ids.stream().findFirst().map(UUID.class::cast);
	}

}

package com.creditbook.auth.infrastructure;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Repository;

import com.creditbook.auth.domain.RevokedTokenRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * {@link RevokedTokenRepository} 구현. 같은 토큰으로 두 번 로그아웃해도 기본 키 위반(500)이 나지 않도록
 * save()/merge 대신 {@code ON CONFLICT (jti) DO NOTHING} 네이티브 INSERT 를 쓴다. 값은 모두 파라미터로 바인딩한다.
 * 호출하는 쪽의 트랜잭션 안에서 실행돼야 한다.
 */
@Repository
class RevokedTokenRepositoryImpl implements RevokedTokenRepository {

	@PersistenceContext
	private EntityManager em;

	@Override
	public void revoke(UUID jti, UUID employeeId, Instant expiresAt, Instant revokedAt) {
		em.createNativeQuery("""
				INSERT INTO revoked_tokens (jti, employee_id, expires_at, revoked_at)
				VALUES (:jti, :employeeId, :expiresAt, :revokedAt)
				ON CONFLICT (jti) DO NOTHING
				""")
				.setParameter("jti", jti)
				.setParameter("employeeId", employeeId)
				.setParameter("expiresAt", expiresAt)
				.setParameter("revokedAt", revokedAt)
				.executeUpdate();
	}

}

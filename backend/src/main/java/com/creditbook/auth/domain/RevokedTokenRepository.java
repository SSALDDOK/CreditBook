package com.creditbook.auth.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 로그아웃한 토큰 목록 (revoked_tokens). 구현은 infrastructure 에 있다.
 */
public interface RevokedTokenRepository {

	/**
	 * 토큰을 폐기 목록에 넣는다. 이미 들어 있으면 아무 일도 하지 않는다 — 같은 토큰으로 두 번 로그아웃해도 오류가 나지 않는다.
	 *
	 * @param jti 토큰 ID
	 * @param employeeId 토큰 주인
	 * @param expiresAt 토큰 만료 시각 (이후로는 행을 정리해도 된다)
	 * @param revokedAt 폐기 시각 (서버 Clock)
	 */
	void revoke(UUID jti, UUID employeeId, Instant expiresAt, Instant revokedAt);

}

package com.creditbook.global.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

/**
 * 직원 세션 토큰 발급·해석.
 * <p>
 * 클레임은 sub(직원 ID)·jti(토큰 ID)·iat·exp 만 싣는다. 역할은 싣지 않는다 — 권한은 요청마다 DB 의 역할로 정한다 (REQ-16).
 * 갱신 토큰은 없다. 만료되면 다시 로그인한다.
 */
@Component
public class JwtTokenService {

	private final JwtEncoder encoder;
	private final JwtDecoder decoder;
	private final Duration tokenTtl;
	private final Clock clock;

	public JwtTokenService(JwtEncoder encoder, JwtDecoder decoder, AuthProperties properties, Clock clock) {
		this.encoder = encoder;
		this.decoder = decoder;
		this.tokenTtl = properties.tokenTtl();
		this.clock = clock;
	}

	/**
	 * 직원에게 새 토큰을 발급한다. JWT 의 시각은 초 단위이므로 발급 시각을 초로 맞춰 응답의 만료 시각과 토큰의 exp 를 일치시킨다.
	 */
	public IssuedToken issue(UUID employeeId) {
		Objects.requireNonNull(employeeId, "employeeId");
		Instant issuedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
		Instant expiresAt = issuedAt.plus(tokenTtl);
		JwtClaimsSet claims = JwtClaimsSet.builder()
				.subject(employeeId.toString())
				.id(UUID.randomUUID().toString())
				.issuedAt(issuedAt)
				.expiresAt(expiresAt)
				.build();
		JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
		String tokenValue = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
		return new IssuedToken(tokenValue, expiresAt, tokenTtl);
	}

	/**
	 * 서명·만료가 유효한 토큰이면 그 내용을 돌려준다. 변조·만료·형식 오류는 예외 없이 빈 값이다 (로그아웃처럼 실패해도 되는 곳에서 쓴다).
	 * 직원 상태·폐기 여부는 보지 않는다.
	 */
	public Optional<VerifiedToken> verify(String tokenValue) {
		if (tokenValue == null || tokenValue.isBlank()) {
			return Optional.empty();
		}
		try {
			Jwt jwt = decoder.decode(tokenValue);
			return Optional.of(new VerifiedToken(
					UUID.fromString(jwt.getSubject()), UUID.fromString(jwt.getId()), jwt.getExpiresAt()));
		}
		catch (JwtException | IllegalArgumentException ex) {
			return Optional.empty();
		}
	}

	/**
	 * 발급한 토큰. {@link #toString()} 은 토큰 원문을 가린다.
	 *
	 * @param value 토큰 원문 — 쿠키에만 싣는다
	 * @param expiresAt 만료 시각 (= exp)
	 * @param ttl 유효 시간 (= 쿠키 Max-Age)
	 */
	public record IssuedToken(String value, Instant expiresAt, Duration ttl) {

		@Override
		public String toString() {
			return "IssuedToken{value=[PROTECTED], expiresAt=" + expiresAt + "}";
		}

	}

	/** 서명·만료를 통과한 토큰의 내용. */
	public record VerifiedToken(UUID employeeId, UUID jti, Instant expiresAt) {
	}

}

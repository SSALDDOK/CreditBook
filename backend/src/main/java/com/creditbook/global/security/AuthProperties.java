package com.creditbook.global.security;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 인증 설정 ({@code creditbook.auth.*}).
 * <p>
 * 비밀키는 환경변수 {@code CREDITBOOK_JWT_SECRET} 로만 받는다 — 리포·설정 파일 어디에도 값을 두지 않는다 (절대 금지 #5).
 * 없거나 32바이트(HS256 키 길이) 미만이면 기동을 멈춘다. 오류 메시지·{@link #toString()} 에 키 값을 넣지 않는다.
 *
 * @param jwtSecret HS256 서명 키 (UTF-8 바이트를 그대로 키로 쓴다)
 * @param tokenTtl 토큰 유효 시간. 쿠키 Max-Age 도 같은 값이다
 */
@ConfigurationProperties(prefix = "creditbook.auth")
public record AuthProperties(String jwtSecret, Duration tokenTtl) {

	/** HS256 은 256비트 이상의 키를 요구한다. */
	static final int MIN_SECRET_BYTES = 32;

	public AuthProperties {
		if (jwtSecret == null || jwtSecret.isBlank()) {
			throw new IllegalStateException(
					"JWT 서명 키가 없습니다. 환경변수 CREDITBOOK_JWT_SECRET 을 설정하세요 (docs/dev-environment-guide.md).");
		}
		if (jwtSecret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
			throw new IllegalStateException(
					"JWT 서명 키가 너무 짧습니다. CREDITBOOK_JWT_SECRET 은 " + MIN_SECRET_BYTES + "바이트 이상이어야 합니다.");
		}
		if (tokenTtl == null || tokenTtl.isNegative() || tokenTtl.isZero()) {
			throw new IllegalStateException("creditbook.auth.token-ttl 은 0보다 커야 합니다.");
		}
	}

	byte[] jwtSecretBytes() {
		return jwtSecret.getBytes(StandardCharsets.UTF_8);
	}

	/** 키 값을 로그·오류 메시지로 흘리지 않도록 가린다. */
	@Override
	public String toString() {
		return "AuthProperties{jwtSecret=[PROTECTED], tokenTtl=" + tokenTtl + "}";
	}

}

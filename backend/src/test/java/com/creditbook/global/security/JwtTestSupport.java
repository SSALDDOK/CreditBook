package com.creditbook.global.security;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;

import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;

/**
 * 스프링 없이 운영과 같은 JWT 발급기·검증기를 만드는 단위 테스트 도구. 키는 호출할 때마다 무작위다 (고정 키 문자열을 두지 않는다).
 */
public final class JwtTestSupport {

	public static final Duration TTL = Duration.ofHours(16);

	private static final SecureRandom RANDOM = new SecureRandom();

	private JwtTestSupport() {
	}

	/** 무작위 키(64바이트)와 16시간 유효 시간의 설정. */
	public static AuthProperties randomProperties() {
		return new AuthProperties(randomSecret(48), TTL);
	}

	/** {@code bytes} 바이트 난수를 Base64 로 바꾼 문자열 (길이 = 4 × ⌈bytes/3⌉). */
	public static String randomSecret(int bytes) {
		byte[] buffer = new byte[bytes];
		RANDOM.nextBytes(buffer);
		return Base64.getEncoder().encodeToString(buffer);
	}

	public static JwtEncoder encoder(AuthProperties properties) {
		return new JwtConfig().jwtEncoder(properties);
	}

	public static JwtDecoder decoder(AuthProperties properties, Clock clock) {
		return new JwtConfig().jwtDecoder(properties, clock);
	}

	public static JwtTokenService tokenService(AuthProperties properties, Clock clock) {
		return new JwtTokenService(encoder(properties), decoder(properties, clock), properties, clock);
	}

	/** 테스트가 시각을 옮길 수 있는 Clock. */
	public static final class MutableClock extends Clock {

		private Instant now;

		public MutableClock(Instant now) {
			this.now = now;
		}

		public void set(Instant instant) {
			this.now = instant;
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}

	}

}

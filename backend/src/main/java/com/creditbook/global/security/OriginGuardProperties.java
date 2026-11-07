package com.creditbook.global.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 프록시 경유 확인 ({@code creditbook.origin-guard.secret}). 화면 배포처(Vercel)가 {@code /api/*} 를 이 서버로 넘길 때
 * 요청마다 붙이는 비밀 헤더 값이다. 값은 환경변수 {@code CREDITBOOK_ORIGIN_SECRET} 로만 받는다 (절대 금지 #5).
 * <p>
 * 비어 있으면 확인을 끈다 — 로컬·테스트·CI E2E 처럼 프록시 없이 직접 붙는 실행. 값을 넣으면 32자 이상이어야 하고, 짧으면 기동을 멈춘다.
 *
 * @param secret 프록시가 붙이는 헤더 값. 비어 있으면 확인하지 않는다
 */
@ConfigurationProperties(prefix = "creditbook.origin-guard")
public record OriginGuardProperties(String secret) {

	static final int MIN_SECRET_LENGTH = 32;

	public OriginGuardProperties {
		secret = secret == null ? "" : secret.strip();
		if (!secret.isEmpty() && secret.length() < MIN_SECRET_LENGTH) {
			throw new IllegalStateException(
					"creditbook.origin-guard.secret 는 " + MIN_SECRET_LENGTH + "자 이상이어야 합니다 (비우면 확인을 끈다).");
		}
	}

	boolean isEnabled() {
		return !secret.isEmpty();
	}

}

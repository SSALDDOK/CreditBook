package com.creditbook.global.security;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * CORS 허용 출처 ({@code creditbook.cors.allowed-origins}). 기본은 빈 목록 — 프런트를 같은 출처(프록시)로 배포하면 CORS 가 필요 없다.
 * 쿠키를 함께 보내야 하므로 {@code *} 는 받지 않는다 (기동 실패).
 */
@ConfigurationProperties(prefix = "creditbook.cors")
public record CorsProperties(List<String> allowedOrigins) {

	public CorsProperties {
		allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
		if (allowedOrigins.stream().anyMatch(origin -> origin.contains("*"))) {
			throw new IllegalStateException("creditbook.cors.allowed-origins 에 '*' 를 쓸 수 없습니다. 출처를 정확히 적으세요.");
		}
	}

	public boolean isEnabled() {
		return !allowedOrigins.isEmpty();
	}

}

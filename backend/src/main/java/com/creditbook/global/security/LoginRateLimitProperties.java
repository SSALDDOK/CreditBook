package com.creditbook.global.security;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 로그인 IP 레이트 리밋 기준 ({@code creditbook.auth.login-rate-limit.*}, REQ-15). 같은 IP 의 로그인 요청을
 * {@code window} 길이의 고정 창마다 {@code maxRequests} 번까지 받고, 넘으면 429 로 거절한다.
 * 값이 없거나 1 미만·0 이하이면 기동을 멈춘다.
 * <p>
 * 환경변수로 덮을 때: {@code CREDITBOOK_AUTH_LOGINRATELIMIT_MAXREQUESTS}, {@code CREDITBOOK_AUTH_LOGINRATELIMIT_WINDOW}
 * (CI E2E 처럼 한 IP 에서 로그인을 많이 하는 실행만 올린다).
 *
 * @param maxRequests 창 하나에서 받는 로그인 요청 수 (기본 설정 10)
 * @param window 창 길이 (기본 설정 1m)
 */
@ConfigurationProperties(prefix = "creditbook.auth.login-rate-limit")
public record LoginRateLimitProperties(Integer maxRequests, Duration window) {

	public LoginRateLimitProperties {
		if (maxRequests == null || maxRequests < 1) {
			throw new IllegalStateException("creditbook.auth.login-rate-limit.max-requests 는 1 이상이어야 합니다.");
		}
		if (window == null || window.isNegative() || window.isZero()) {
			throw new IllegalStateException("creditbook.auth.login-rate-limit.window 는 0보다 커야 합니다.");
		}
	}

}

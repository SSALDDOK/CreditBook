package com.creditbook.global.security;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 초대 코드 가입 IP 레이트 리밋 기준 ({@code creditbook.auth.signup-rate-limit.*}, REQ-36). 같은 IP 의 가입 요청(POST /api/setup)을
 * {@code window} 길이의 고정 창마다 {@code maxRequests} 번까지 받고, 넘으면 429 SIGNUP_RATE_LIMITED 로 거절한다.
 * 성공·실패와 관계없이 모든 요청을 센다 — 초대 코드 대입을 막는다. 값이 없거나 1 미만·0 이하이면 기동을 멈춘다.
 * <p>
 * 환경변수로 덮을 때: {@code CREDITBOOK_AUTH_SIGNUPRATELIMIT_MAXREQUESTS}, {@code CREDITBOOK_AUTH_SIGNUPRATELIMIT_WINDOW}
 * (CI E2E 처럼 한 IP 에서 가입을 많이 하는 실행만 올린다).
 *
 * @param maxRequests 창 하나에서 받는 가입 요청 수 (기본 설정 5)
 * @param window 창 길이 (기본 설정 10m)
 */
@ConfigurationProperties(prefix = "creditbook.auth.signup-rate-limit")
public record SignupRateLimitProperties(Integer maxRequests, Duration window) {

	public SignupRateLimitProperties {
		if (maxRequests == null || maxRequests < 1) {
			throw new IllegalStateException("creditbook.auth.signup-rate-limit.max-requests 는 1 이상이어야 합니다.");
		}
		if (window == null || window.isNegative() || window.isZero()) {
			throw new IllegalStateException("creditbook.auth.signup-rate-limit.window 는 0보다 커야 합니다.");
		}
	}

}

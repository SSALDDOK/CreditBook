package com.creditbook.auth.application;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 로그인 잠금 기준 ({@code creditbook.auth.login-lock.*}, REQ-15). 있는 계정의 연속 실패가 {@code maxFailures} 번이 되면
 * {@code duration} 동안 잠근다. 잠금 시간은 늘리지 않는다.
 * 값이 없거나 1회 미만·0 이하이면 기동을 멈춘다 — 잠금이 조용히 꺼진 채 배포되지 않게 하기 위해서다.
 *
 * @param maxFailures 잠그는 연속 실패 횟수 (기본 설정 5)
 * @param duration 잠금 시간 (기본 설정 5m)
 */
@ConfigurationProperties(prefix = "creditbook.auth.login-lock")
public record LoginLockProperties(Integer maxFailures, Duration duration) {

	public LoginLockProperties {
		if (maxFailures == null || maxFailures < 1) {
			throw new IllegalStateException("creditbook.auth.login-lock.max-failures 는 1 이상이어야 합니다.");
		}
		if (duration == null || duration.isNegative() || duration.isZero()) {
			throw new IllegalStateException("creditbook.auth.login-lock.duration 은 0보다 커야 합니다.");
		}
	}

}

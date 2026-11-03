package com.creditbook.auth.domain;

import java.time.Duration;
import java.util.Objects;

/**
 * 로그인을 연속으로 실패해 잠긴 계정 (REQ-15). 잠긴 동안은 비밀번호가 맞아도 로그인할 수 없다.
 * 잠금을 건 실패 응답과 잠긴 동안의 시도 모두 이 예외로 거절한다.
 */
public class AccountLockedException extends RuntimeException {

	private final long retryAfterSeconds;

	/**
	 * @param remaining 잠금이 풀릴 때까지 남은 시간. 초 단위로 올림한다 (0.5초 남음 → 1초) — 알려 준 시각에 다시 시도하면 풀려 있게
	 */
	public AccountLockedException(Duration remaining) {
		super("로그인을 5회 실패해 계정이 잠겼습니다. 잠시 후 다시 시도해 주세요.");
		this.retryAfterSeconds = ceilSeconds(Objects.requireNonNull(remaining, "remaining"));
	}

	/** 다시 시도할 수 있을 때까지 남은 초 (1 이상). */
	public long getRetryAfterSeconds() {
		return retryAfterSeconds;
	}

	private static long ceilSeconds(Duration remaining) {
		long seconds = remaining.getSeconds() + (remaining.getNano() > 0 ? 1 : 0);
		return Math.max(seconds, 1);
	}

}

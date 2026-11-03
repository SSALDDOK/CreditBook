package com.creditbook.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 로그인 잠금 설정값 검증 — 잘못된 값이면 기동 시점에 멈춘다 (잠금이 조용히 꺼진 채 배포되지 않게).
 */
@Tag("REQ-15")
class LoginLockPropertiesTest {

	@Test
	@DisplayName("기준 횟수와 잠금 시간을 그대로 담는다")
	void keeps_values() {
		// when
		LoginLockProperties properties = new LoginLockProperties(5, Duration.ofMinutes(5));

		// then
		assertThat(properties.maxFailures()).isEqualTo(5);
		assertThat(properties.duration()).isEqualTo(Duration.ofMinutes(5));
	}

	@ParameterizedTest(name = "[{index}] maxFailures={0}")
	@ValueSource(ints = { 0, -1 })
	@DisplayName("기준 횟수가 1 미만이면 기동을 멈춘다")
	void max_failures_must_be_positive(int maxFailures) {
		assertThatThrownBy(() -> new LoginLockProperties(maxFailures, Duration.ofMinutes(5)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("max-failures");
	}

	@ParameterizedTest(name = "[{index}] {0}초")
	@ValueSource(longs = { 0, -1 })
	@DisplayName("잠금 시간이 0 이하이면 기동을 멈춘다")
	void duration_must_be_positive(long seconds) {
		assertThatThrownBy(() -> new LoginLockProperties(5, Duration.ofSeconds(seconds)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("duration");
	}

	@Test
	@DisplayName("값이 없으면 기동을 멈춘다")
	void missing_values_fail() {
		assertThatThrownBy(() -> new LoginLockProperties(null, Duration.ofMinutes(5)))
				.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> new LoginLockProperties(5, null)).isInstanceOf(IllegalStateException.class);
	}

}

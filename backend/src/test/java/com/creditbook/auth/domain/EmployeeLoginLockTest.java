package com.creditbook.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 로그인 잠금 규칙 (REQ-15): 있는 계정의 연속 실패 5회 → 5분 잠금, 잠긴 동안은 맞아도 거절, 성공·잠금 만료 뒤에는 처음부터 센다.
 * 기준 횟수·잠금 시간은 설정값이라 메서드 인자로 받는다 — 여기서는 기본 설정(5회·5분)으로 검증한다.
 */
@Tag("REQ-15")
class EmployeeLoginLockTest {

	private static final UUID STORE_ID = UUID.fromString("00000000-0000-0000-0000-000000005001");

	private static final Instant CREATED_AT = Instant.parse("2026-10-01T00:00:00Z");
	private static final Instant NOW = Instant.parse("2026-11-03T01:00:00Z");
	private static final String HASH = "$2a$10$abcdefghijklmnopqrstuuJ8m0zW8V0u1Yy3s3b4c5d6e7f8g9h0i";
	private static final int MAX_FAILURES = 5;
	private static final Duration LOCK = Duration.ofMinutes(5);

	private Employee employee;

	@BeforeEach
	void setUp() {
		employee = Employee.createStaff(STORE_ID, "staff1", HASH, "이직원", CREATED_AT);
	}

	private boolean fail(Instant at) {
		return employee.recordLoginFailure(at, MAX_FAILURES, LOCK);
	}

	private void failTimes(int times, Instant at) {
		for (int i = 0; i < times; i++) {
			fail(at);
		}
	}

	@Test
	@DisplayName("새 직원은 실패 횟수 0, 잠기지 않은 상태로 시작한다")
	void new_employee_is_not_locked() {
		// then
		assertThat(employee.getFailedLoginCount()).isZero();
		assertThat(employee.getLockedUntil()).isNull();
		assertThat(employee.isLocked(NOW)).isFalse();
		assertThat(employee.lockRemaining(NOW)).isEqualTo(Duration.ZERO);
	}

	@ParameterizedTest(name = "[{index}] {0}번 틀림 → 잠김 {1}")
	@CsvSource({ "1, false", "4, false", "5, true" })
	@DisplayName("연속 5회 틀리면 계정이 잠기고, 4회까지는 잠기지 않는다")
	void locks_on_fifth_consecutive_failure(int failures, boolean expectedLocked) {
		// given
		failTimes(failures - 1, NOW);

		// when
		boolean lockedNow = fail(NOW);

		// then
		assertThat(lockedNow).isEqualTo(expectedLocked);
		assertThat(employee.isLocked(NOW)).isEqualTo(expectedLocked);
		assertThat(employee.getFailedLoginCount()).isEqualTo(failures);
	}

	@Test
	@DisplayName("5번째 실패로 잠기면 잠금 끝 시각은 그 실패 시각 + 5분이고, 남은 시간은 5분이다")
	void lock_lasts_five_minutes_from_fifth_failure() {
		// given
		failTimes(4, NOW.minusSeconds(30));

		// when
		fail(NOW);

		// then
		assertThat(employee.getLockedUntil()).isEqualTo(NOW.plus(LOCK));
		assertThat(employee.lockRemaining(NOW)).isEqualTo(LOCK);
	}

	@ParameterizedTest(name = "[{index}] 잠긴 뒤 {0}초 → 잠김 {1}")
	@CsvSource({ "0, true", "1, true", "299, true", "300, false", "301, false" })
	@DisplayName("잠긴 지 4분 59초까지는 잠겨 있고, 5분 0초가 되면 풀린다")
	void lock_expires_exactly_after_five_minutes(long elapsedSeconds, boolean expectedLocked) {
		// given
		failTimes(MAX_FAILURES, NOW);

		// when
		Instant at = NOW.plusSeconds(elapsedSeconds);

		// then
		assertThat(employee.isLocked(at)).isEqualTo(expectedLocked);
		assertThat(employee.lockRemaining(at))
				.isEqualTo(expectedLocked ? LOCK.minusSeconds(elapsedSeconds) : Duration.ZERO);
	}

	@Test
	@DisplayName("잠긴 동안에는 실패를 기록하지 않는다 — 비밀번호를 대조하지 않고 거절하므로 잠금 시간도 늘지 않는다")
	void failure_while_locked_is_rejected() {
		// given
		failTimes(MAX_FAILURES, NOW);
		Instant whileLocked = NOW.plusSeconds(60);

		// when / then
		assertThatThrownBy(() -> fail(whileLocked)).isInstanceOf(IllegalStateException.class);
		assertThat(employee.getFailedLoginCount()).isEqualTo(MAX_FAILURES);
		assertThat(employee.getLockedUntil()).isEqualTo(NOW.plus(LOCK));
	}

	@Test
	@DisplayName("잠금이 풀린 뒤 다시 1회 틀리면 실패 횟수는 1부터 세고 잠기지 않는다")
	void failure_after_lock_expiry_counts_from_one() {
		// given
		failTimes(MAX_FAILURES, NOW);
		Instant afterExpiry = NOW.plus(LOCK);

		// when
		boolean lockedNow = fail(afterExpiry);

		// then
		assertThat(lockedNow).isFalse();
		assertThat(employee.getFailedLoginCount()).isEqualTo(1);
		assertThat(employee.getLockedUntil()).isNull();
		assertThat(employee.isLocked(afterExpiry)).isFalse();
	}

	@Test
	@DisplayName("잠금이 풀린 뒤 다시 5회 틀리면 또 5분 잠긴다 — 잠금 시간은 늘어나지 않는다")
	void second_lock_is_also_five_minutes() {
		// given
		failTimes(MAX_FAILURES, NOW);
		Instant afterExpiry = NOW.plus(LOCK).plusSeconds(10);

		// when
		failTimes(MAX_FAILURES, afterExpiry);

		// then
		assertThat(employee.getLockedUntil()).isEqualTo(afterExpiry.plus(LOCK));
		assertThat(employee.lockRemaining(afterExpiry)).isEqualTo(LOCK);
	}

	@Test
	@DisplayName("로그인에 성공하면 실패 횟수와 잠금 끝 시각이 초기화되고, 프로필 변경 시각은 그대로다")
	void successful_login_resets_failures() {
		// given
		failTimes(MAX_FAILURES - 1, NOW);

		// when
		employee.recordLogin(NOW.plusSeconds(5));

		// then
		assertThat(employee.getFailedLoginCount()).isZero();
		assertThat(employee.getLockedUntil()).isNull();
		assertThat(employee.getUpdatedAt()).isEqualTo(CREATED_AT);
	}

	@Test
	@DisplayName("성공 뒤에는 다시 5회 연속으로 틀려야 잠긴다 — 성공 전 실패는 이어서 세지 않는다")
	void failures_before_success_are_not_carried_over() {
		// given
		failTimes(MAX_FAILURES - 1, NOW);
		employee.recordLogin(NOW.plusSeconds(1));

		// when
		boolean lockedNow = fail(NOW.plusSeconds(2));

		// then
		assertThat(lockedNow).isFalse();
		assertThat(employee.getFailedLoginCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("실패·잠금 기록은 프로필 변경 시각을 바꾸지 않는다")
	void failures_do_not_touch_updated_at() {
		// when
		failTimes(MAX_FAILURES, NOW);

		// then
		assertThat(employee.getUpdatedAt()).isEqualTo(CREATED_AT);
	}

	@ParameterizedTest(name = "[{index}] maxFailures={0}")
	@ValueSource(ints = { 0, -1 })
	@DisplayName("기준 횟수가 1 미만이면 실패 기록을 거절한다")
	void max_failures_must_be_positive(int maxFailures) {
		assertThatThrownBy(() -> employee.recordLoginFailure(NOW, maxFailures, LOCK))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@ParameterizedTest(name = "[{index}] {0}초")
	@ValueSource(longs = { 0, -1 })
	@DisplayName("잠금 시간이 0 이하이면 실패 기록을 거절한다")
	void lock_duration_must_be_positive(long seconds) {
		assertThatThrownBy(() -> employee.recordLoginFailure(NOW, MAX_FAILURES, Duration.ofSeconds(seconds)))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@ParameterizedTest(name = "[{index}] 남은 {0}ms → {1}초")
	@CsvSource({ "300000, 300", "299500, 300", "1000, 1", "500, 1", "1, 1", "0, 1" })
	@DisplayName("잠금 응답의 남은 초는 올림한 정수이고 1초보다 작지 않다")
	void retry_after_seconds_is_rounded_up(long remainingMillis, long expectedSeconds) {
		// when
		AccountLockedException ex = new AccountLockedException(Duration.ofMillis(remainingMillis));

		// then
		assertThat(ex.getRetryAfterSeconds()).isEqualTo(expectedSeconds);
		assertThat(ex.getMessage()).isEqualTo("로그인을 5회 실패해 계정이 잠겼습니다. 잠시 후 다시 시도해 주세요.");
	}

	@Test
	@DisplayName("잠긴 지 4분 59초 지나 남은 1초는 다시 시도까지 1초로 알린다")
	void retry_after_one_second_left() {
		// given
		failTimes(MAX_FAILURES, NOW);
		Instant at = NOW.plus(LOCK).minusSeconds(1);

		// when
		AccountLockedException ex = new AccountLockedException(employee.lockRemaining(at));

		// then
		assertThat(ex.getRetryAfterSeconds()).isEqualTo(1);
	}

}

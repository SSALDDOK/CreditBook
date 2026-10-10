package com.creditbook.prepaid.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("REQ-10")
class IdempotencyKeyTest {

	/** 64자 (경계 — 허용) */
	private static final String KEY_64 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
	/** 65자 (경계 — 거절) */
	private static final String KEY_65 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

	@ParameterizedTest(name = "[{index}] {0}")
	@ValueSource(strings = { "a", "3f2b8c1d-9e4a-4b7c-8d6e-0f1a2b3c4d5e", "ABC-def-123", KEY_64 })
	@DisplayName("요청 키는 영문·숫자·하이픈 1–64자면 받는다")
	void accepts_valid_key(String value) {
		// when
		IdempotencyKey key = IdempotencyKey.of(value);

		// then
		assertThat(key.value()).isEqualTo(value);
	}

	@ParameterizedTest(name = "[{index}] \"{0}\"")
	@NullAndEmptySource
	@ValueSource(strings = { KEY_65, "key_1", "key 1", " key", "key\n", "요청키", "key/1", "key%20" })
	@DisplayName("요청 키가 없거나 65자 이상이거나 영문·숫자·하이픈 밖의 문자가 있으면 거절하고 입력값을 메시지에 담지 않는다")
	void rejects_missing_or_malformed_key(String value) {
		// when / then
		assertThatThrownBy(() -> IdempotencyKey.of(value))
				.isInstanceOf(InvalidIdempotencyKeyException.class)
				.hasMessage("요청 키(Idempotency-Key)를 확인해 주세요.");
	}

	@Test
	@DisplayName("로그에는 요청 키의 앞 8자까지만 남긴다")
	void log_prefix_is_at_most_eight_chars() {
		// given
		IdempotencyKey longKey = IdempotencyKey.of("3f2b8c1d-9e4a-4b7c-8d6e-0f1a2b3c4d5e");
		IdempotencyKey shortKey = IdempotencyKey.of("abc");

		// when / then
		assertThat(longKey.logPrefix()).isEqualTo("3f2b8c1d");
		assertThat(shortKey.logPrefix()).isEqualTo("abc");
	}

}

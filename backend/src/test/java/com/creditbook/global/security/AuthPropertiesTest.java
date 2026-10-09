package com.creditbook.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 인증·CORS 설정값 검증 — 잘못된 값이면 기동 시점에 멈춘다.
 */
@Tag("REQ-15")
class AuthPropertiesTest {

	@ParameterizedTest(name = "[{index}] \"{0}\"")
	@NullAndEmptySource
	@ValueSource(strings = { "   " })
	@DisplayName("서명 키가 없으면 기동을 멈춘다")
	void missing_secret_fails(String secret) {
		assertThatThrownBy(() -> new AuthProperties(secret, Duration.ofHours(16)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("CREDITBOOK_JWT_SECRET");
	}

	@Test
	@DisplayName("서명 키가 32바이트 미만이면 기동을 멈추고, 오류 메시지에 키 값을 넣지 않는다")
	void short_secret_fails_without_leaking_value() {
		// given: 31바이트 (24바이트 난수의 Base64 32자 중 앞 31자)
		String secret = JwtTestSupport.randomSecret(24).substring(0, 31);

		// when / then
		assertThatThrownBy(() -> new AuthProperties(secret, Duration.ofHours(16)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("32바이트")
				.hasMessageNotContaining(secret);
	}

	@Test
	@DisplayName("서명 키가 정확히 32바이트면 받는다")
	void secret_of_32_bytes_is_accepted() {
		// given: 24바이트 난수의 Base64 = 32자
		String secret = JwtTestSupport.randomSecret(24);

		// when
		AuthProperties properties = new AuthProperties(secret, Duration.ofHours(16));

		// then
		assertThat(properties.jwtSecretBytes()).hasSize(32);
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@ValueSource(strings = { "PT0S", "-PT1H" })
	@DisplayName("토큰 유효 시간이 0 이하면 기동을 멈춘다")
	void non_positive_ttl_fails(String ttl) {
		assertThatThrownBy(() -> new AuthProperties(JwtTestSupport.randomSecret(48), Duration.parse(ttl)))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	@DisplayName("설정의 문자열 표현에는 서명 키가 없다")
	void to_string_hides_secret() {
		// given
		String secret = JwtTestSupport.randomSecret(48);

		// when
		String text = new AuthProperties(secret, Duration.ofHours(16)).toString();

		// then
		assertThat(text).doesNotContain(secret).contains("PROTECTED");
	}

	@Test
	@DisplayName("CORS 허용 출처에 '*' 가 있으면 기동을 멈춘다")
	void cors_wildcard_fails() {
		assertThatThrownBy(() -> new CorsProperties(List.of("https://app.example.com", "*")))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	@DisplayName("CORS 허용 출처가 없으면 CORS 를 등록하지 않는다")
	void cors_is_disabled_when_no_origin() {
		assertThat(new CorsProperties(null).isEnabled()).isFalse();
		assertThat(new CorsProperties(List.of()).isEnabled()).isFalse();
		assertThat(new CorsProperties(List.of("https://app.example.com")).isEnabled()).isTrue();
	}

}

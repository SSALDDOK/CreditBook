package com.creditbook.global.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseCookie;

@Tag("REQ-15")
class SessionCookiesTest {

	@Test
	@DisplayName("세션 쿠키는 HttpOnly·Secure·SameSite=Strict·Path=/api 이고 Max-Age 는 토큰 유효 시간과 같다")
	void session_cookie_has_secure_attributes() {
		// when
		ResponseCookie cookie = SessionCookies.issue("token-value", Duration.ofHours(16));

		// then
		assertThat(cookie.getName()).isEqualTo("CB_SESSION");
		assertThat(cookie.getValue()).isEqualTo("token-value");
		assertThat(cookie.isHttpOnly()).isTrue();
		assertThat(cookie.isSecure()).isTrue();
		assertThat(cookie.getSameSite()).isEqualTo("Strict");
		assertThat(cookie.getPath()).isEqualTo("/api");
		assertThat(cookie.getMaxAge()).isEqualTo(Duration.ofSeconds(57_600));
	}

	@Test
	@DisplayName("삭제용 쿠키는 같은 이름·경로에 빈 값과 Max-Age=0 이다")
	void clear_cookie_expires_immediately() {
		// when
		ResponseCookie cookie = SessionCookies.clear();

		// then
		assertThat(cookie.getName()).isEqualTo("CB_SESSION");
		assertThat(cookie.getValue()).isEmpty();
		assertThat(cookie.getPath()).isEqualTo("/api");
		assertThat(cookie.getMaxAge()).isZero();
		assertThat(cookie.isHttpOnly()).isTrue();
		assertThat(cookie.isSecure()).isTrue();
		assertThat(cookie.getSameSite()).isEqualTo("Strict");
	}

}

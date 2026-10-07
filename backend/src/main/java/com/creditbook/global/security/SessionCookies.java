package com.creditbook.global.security;

import java.time.Duration;

import org.springframework.http.ResponseCookie;

/**
 * 세션 토큰 쿠키. 자바스크립트가 읽지 못하게(HttpOnly) 하고, HTTPS 로만(Secure — 끄는 설정 없음), 같은 사이트 요청에만(SameSite=Strict) 보낸다.
 * 경로는 /api 로 좁혀 API 요청에만 실린다.
 */
public final class SessionCookies {

	public static final String NAME = "CB_SESSION";
	public static final String PATH = "/api";

	private SessionCookies() {
	}

	/** 토큰을 담은 쿠키. Max-Age 는 토큰 유효 시간과 같다. */
	public static ResponseCookie issue(String tokenValue, Duration maxAge) {
		return base(tokenValue).maxAge(maxAge).build();
	}

	/** 브라우저의 세션 쿠키를 지우는 쿠키 (같은 이름·경로, Max-Age=0). */
	public static ResponseCookie clear() {
		return base("").maxAge(Duration.ZERO).build();
	}

	private static ResponseCookie.ResponseCookieBuilder base(String value) {
		return ResponseCookie.from(NAME, value)
				.httpOnly(true)
				.secure(true)
				.sameSite("Strict")
				.path(PATH);
	}

}

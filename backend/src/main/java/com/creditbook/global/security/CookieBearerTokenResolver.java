package com.creditbook.global.security;

import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.web.util.matcher.RequestMatcher;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 토큰을 {@value SessionCookies#NAME} 쿠키에서만 읽는다. Authorization 헤더는 보지 않는다.
 * <p>
 * 인증이 필요 없는 경로(로그인·로그아웃·헬스체크)에서는 쿠키가 있어도 읽지 않는다 — 만료되거나 폐기된 쿠키가 남아 있어도
 * 다시 로그인하거나 로그아웃할 수 있어야 하기 때문이다.
 */
class CookieBearerTokenResolver implements BearerTokenResolver {

	private final RequestMatcher publicEndpoints;

	CookieBearerTokenResolver(RequestMatcher publicEndpoints) {
		this.publicEndpoints = publicEndpoints;
	}

	@Override
	public String resolve(HttpServletRequest request) {
		if (publicEndpoints.matches(request)) {
			return null;
		}
		Cookie[] cookies = request.getCookies();
		if (cookies == null) {
			return null;
		}
		for (Cookie cookie : cookies) {
			if (SessionCookies.NAME.equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isBlank()) {
				return cookie.getValue();
			}
		}
		return null;
	}

}

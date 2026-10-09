package com.creditbook.global.security;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import com.creditbook.global.error.ErrorCode;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 인증이 없거나 토큰이 유효하지 않은 요청을 401 UNAUTHENTICATED 로 거절한다.
 * 정상적인 거절이므로 WARN. 로그에는 요청 방식·경로(쿼리 문자열 제외 — 검색어에 연락처가 섞일 수 있다)와 사유 종류만 남기고 토큰 원문은 남기지 않는다.
 */
@Component
class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

	private static final Logger log = LoggerFactory.getLogger(RestAuthenticationEntryPoint.class);

	private final SecurityErrorResponseWriter writer;

	RestAuthenticationEntryPoint(SecurityErrorResponseWriter writer) {
		this.writer = writer;
	}

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException authException) throws IOException {
		log.warn("authentication rejected: method={} path={} reason={}", request.getMethod(), request.getRequestURI(),
				reason(authException));
		writer.write(response, ErrorCode.UNAUTHENTICATED);
	}

	private static String reason(AuthenticationException ex) {
		if (ex instanceof org.springframework.security.authentication.InsufficientAuthenticationException) {
			return "no session";
		}
		return ex.getClass().getSimpleName() + ": " + ex.getMessage();
	}

}

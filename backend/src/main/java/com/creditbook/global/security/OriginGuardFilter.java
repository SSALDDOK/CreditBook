package com.creditbook.global.security;

import static org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher.pathPattern;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import com.creditbook.global.error.ErrorCode;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 프록시를 거치지 않은 요청을 거절한다 (REQ-15 보강, CB-59). 배포에서는 화면 배포처(Vercel)가 {@code /api/*} 를 이 서버로 넘기며
 * {@link #HEADER} 에 비밀 값을 넣는다(방문자가 같은 헤더를 보내도 덮어쓴다). 이 헤더가 없거나 다르면 403 {@code FORBIDDEN}.
 * <p>
 * 왜 필요한가: 로그인 레이트 리밋은 프록시가 넘긴 X-Forwarded-For 로 접속자 IP 를 잡는다. 이 서버 주소로 직접 요청하면 그 헤더를
 * 마음대로 적어 제한을 피할 수 있으므로, 프록시를 거친 요청만 받는다.
 * <p>
 * 헬스 체크({@code GET /actuator/health})는 배포처가 직접 부르므로 확인하지 않는다. 값 비교는 시간 차로 값을 알아낼 수 없게
 * {@link MessageDigest#isEqual} 로 한다. 로그에는 경로와 IP 만 남기고 헤더 값은 남기지 않는다.
 * 설정값이 비어 있으면 필터를 체인에 넣지 않는다 ({@link SecurityConfig}).
 */
final class OriginGuardFilter extends OncePerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger(OriginGuardFilter.class);

	static final String HEADER = "X-Origin-Secret";

	static final RequestMatcher HEALTH = pathPattern(HttpMethod.GET, "/actuator/health");

	private final byte[] expected;
	private final SecurityErrorResponseWriter errorResponseWriter;

	OriginGuardFilter(OriginGuardProperties properties, SecurityErrorResponseWriter errorResponseWriter) {
		if (!properties.isEnabled()) {
			throw new IllegalArgumentException("origin-guard secret 이 비어 있으면 필터를 만들지 않는다");
		}
		this.expected = properties.secret().getBytes(StandardCharsets.UTF_8);
		this.errorResponseWriter = Objects.requireNonNull(errorResponseWriter, "errorResponseWriter");
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return HEALTH.matches(request);
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		String actual = request.getHeader(HEADER);
		if (actual == null || !MessageDigest.isEqual(expected, actual.getBytes(StandardCharsets.UTF_8))) {
			log.warn("direct origin access rejected: path={} ip={}", request.getRequestURI(), request.getRemoteAddr());
			errorResponseWriter.write(response, ErrorCode.FORBIDDEN);
			return;
		}
		filterChain.doFilter(request, response);
	}

}

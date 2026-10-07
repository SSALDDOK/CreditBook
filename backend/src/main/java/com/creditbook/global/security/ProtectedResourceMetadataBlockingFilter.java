package com.creditbook.global.security;

import static org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher.pathPattern;

import java.io.IOException;

import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 리소스 서버 DSL 이 자동으로 여는 보호 리소스 메타데이터 경로({@value #PATH}, RFC 9728)를 막는다.
 * <p>
 * Spring Security 7 은 {@code oauth2ResourceServer()} 를 쓰면 이 경로를 인증 없이 응답하는 필터를 항상 넣고, 끄는 설정이 없다.
 * 공개 경로는 {@link SecurityConfig#PUBLIC_ENDPOINTS} 에 적은 것뿐이어야 하고(REQ-22), 메타데이터의 bearer 전달 방식(header)도
 * 실제(쿠키)와 다르다. 그래서 메타데이터 필터보다 앞에서 이 경로를 다른 없는 경로와 같은 401 UNAUTHENTICATED 로 거절한다.
 * 메타데이터 필터는 인증 필터보다 앞에 있어 인가 규칙으로는 막을 수 없다.
 */
class ProtectedResourceMetadataBlockingFilter extends OncePerRequestFilter {

	static final String PATH = "/.well-known/oauth-protected-resource";

	private static final RequestMatcher METADATA_ENDPOINT = pathPattern(PATH + "/**");

	private final AuthenticationEntryPoint authenticationEntryPoint;

	ProtectedResourceMetadataBlockingFilter(AuthenticationEntryPoint authenticationEntryPoint) {
		this.authenticationEntryPoint = authenticationEntryPoint;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if (METADATA_ENDPOINT.matches(request)) {
			authenticationEntryPoint.commence(request, response,
					new InsufficientAuthenticationException("protected resource metadata endpoint is disabled"));
			return;
		}
		chain.doFilter(request, response);
	}

}

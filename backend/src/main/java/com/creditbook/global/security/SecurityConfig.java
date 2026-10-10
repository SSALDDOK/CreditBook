package com.creditbook.global.security;

import static org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher.pathPattern;

import java.util.List;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.LogoutFilter;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * 웹 보안 구성 (CB-42). 기본은 거부 — 아래 공개 경로 말고는 모두 로그인이 필요하다 (없는 경로도 401).
 * <ul>
 * <li>공개: POST /api/auth/login, POST /api/auth/logout, GET /actuator/health, /error</li>
 * <li>ADMIN 전용: {@link #ADMIN_ENDPOINTS} (REQ-16). STAFF 가 부르면 403</li>
 * <li>리소스 서버 DSL 이 자동으로 여는 메타데이터 경로는 {@link ProtectedResourceMetadataBlockingFilter} 가 401 로 막는다</li>
 * <li>인증: {@value SessionCookies#NAME} 쿠키의 JWT 만 받는다 (Authorization 헤더 미사용). 서버 세션 없음(STATELESS)</li>
 * <li>401·403 은 {@link RestAuthenticationEntryPoint}·{@link RestAccessDeniedHandler} 가 ErrorResponse 형식으로 응답한다.
 * 진입점은 일반 예외 처리와 리소스 서버(토큰 오류) 두 곳에 모두 지정한다 — 한쪽만 지정하면 토큰 오류가 기본 응답(빈 본문)으로 나간다</li>
 * </ul>
 * <p>
 * <b>CSRF 보호를 끄는 근거</b>: 세션 쿠키가 SameSite=Strict 라 다른 사이트에서 시작한 요청에는 실리지 않고, 프런트는 같은 출처(프록시)로
 * 배포하며, 상태를 바꾸는 API 는 JSON 본문(@RequestBody, application/json)을 받거나 PATCH 다 — 폼 전송(GET·POST 만 가능)으로는
 * 만들 수 없는 요청이다.
 * 켜 두면 CSRF 토큰이 없는 로그인 POST 부터 403 이 된다.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableConfigurationProperties(CorsProperties.class)
public class SecurityConfig {

	/** 로그인 없이 부를 수 있는 경로. 이 경로에서는 쿠키의 토큰을 읽지도 않는다 ({@link CookieBearerTokenResolver}). */
	static final RequestMatcher PUBLIC_ENDPOINTS = new OrRequestMatcher(
			pathPattern(HttpMethod.POST, "/api/auth/login"),
			pathPattern(HttpMethod.POST, "/api/auth/logout"),
			pathPattern(HttpMethod.GET, "/actuator/health"),
			pathPattern("/error"));

	/** 사장(ADMIN)만 부를 수 있는 경로 (REQ-16). 그 밖의 로그인 필요 경로는 ADMIN·STAFF 모두 쓴다. */
	static final RequestMatcher ADMIN_ENDPOINTS = new OrRequestMatcher(
			pathPattern(HttpMethod.PATCH, "/api/customers/{id}/deactivate"));

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, EmployeeJwtAuthenticationConverter authenticationConverter,
			RestAuthenticationEntryPoint authenticationEntryPoint, RestAccessDeniedHandler accessDeniedHandler,
			CorsProperties corsProperties) throws Exception {
		http
				.csrf(AbstractHttpConfigurer::disable)
				.formLogin(AbstractHttpConfigurer::disable)
				.httpBasic(AbstractHttpConfigurer::disable)
				.logout(AbstractHttpConfigurer::disable)
				.requestCache(AbstractHttpConfigurer::disable)
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(authorize -> authorize
						.requestMatchers(PUBLIC_ENDPOINTS).permitAll()
						.requestMatchers(ADMIN_ENDPOINTS).hasRole("ADMIN")
						.anyRequest().authenticated())
				.exceptionHandling(exceptions -> exceptions
						.authenticationEntryPoint(authenticationEntryPoint)
						.accessDeniedHandler(accessDeniedHandler))
				.oauth2ResourceServer(resourceServer -> resourceServer
						.bearerTokenResolver(new CookieBearerTokenResolver(PUBLIC_ENDPOINTS))
						.authenticationEntryPoint(authenticationEntryPoint)
						.accessDeniedHandler(accessDeniedHandler)
						.jwt(jwt -> jwt.jwtAuthenticationConverter(authenticationConverter)));
		// DSL 이 자동으로 여는 메타데이터 경로를 막는다. 메타데이터 필터(인증 필터 바로 앞)보다 확실히 앞에 두려고 LogoutFilter 자리를 기준으로 한다
		http.addFilterBefore(new ProtectedResourceMetadataBlockingFilter(authenticationEntryPoint), LogoutFilter.class);
		if (corsProperties.isEnabled()) {
			http.cors(cors -> cors.configurationSource(corsConfigurationSource(corsProperties)));
		}
		else {
			http.cors(AbstractHttpConfigurer::disable);
		}
		return http.build();
	}

	/**
	 * BCrypt 를 직접 쓴다. DelegatingPasswordEncoder 는 해시 앞에 {bcrypt} 접두사를 요구해, 접두사 없는 BCrypt 해시와 대조하지 못한다.
	 */
	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	/** 허용 출처가 설정됐을 때만 쓴다. 쿠키를 보내야 하므로 allowCredentials. 사전 요청(OPTIONS)은 CorsFilter 가 응답한다. */
	private static UrlBasedCorsConfigurationSource corsConfigurationSource(CorsProperties properties) {
		CorsConfiguration configuration = new CorsConfiguration();
		configuration.setAllowedOrigins(properties.allowedOrigins());
		configuration.setAllowedMethods(List.of("GET", "POST", "PATCH", "OPTIONS"));
		configuration.setAllowedHeaders(List.of("Content-Type", "Idempotency-Key"));
		configuration.setAllowCredentials(true);
		configuration.setMaxAge(3600L);
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/api/**", configuration);
		return source;
	}

}

package com.creditbook.global.security;

import static org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher.pathPattern;

import java.time.Clock;
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
 * <li>비밀번호를 바꿔야 하는 계정(REQ-30)은 {@link #PASSWORD_CHANGE_ALLOWED_ENDPOINTS} 와 로그아웃만 쓸 수 있다.
 * 그 밖은 403 PASSWORD_CHANGE_REQUIRED — 이 계정은 역할 대신 비밀번호 변경 필요 권한만 받으므로 역할 검사에서 걸린다</li>
 * <li>ADMIN 전용: {@link #ADMIN_ENDPOINTS} (REQ-16·30). STAFF 가 부르면 403</li>
 * <li>그 밖의 경로는 ADMIN·STAFF 역할이 있어야 한다 (로그인하지 않았으면 401)</li>
 * <li>리소스 서버 DSL 이 자동으로 여는 메타데이터 경로는 {@link ProtectedResourceMetadataBlockingFilter} 가 401 로 막는다</li>
 * <li>인증: {@value SessionCookies#NAME} 쿠키의 JWT 만 받는다 (Authorization 헤더 미사용). 서버 세션 없음(STATELESS)</li>
 * <li>로그인 IP 레이트 리밋: {@link LoginRateLimitFilter} 가 POST /api/auth/login 을 IP 별로 세어 넘으면 429 (REQ-15).
 * CORS·보안 헤더 필터 뒤, 인증 필터 앞이다</li>
 * <li>401·403 은 {@link RestAuthenticationEntryPoint}·{@link RestAccessDeniedHandler} 가 ErrorResponse 형식으로 응답한다.
 * 진입점은 일반 예외 처리와 리소스 서버(토큰 오류) 두 곳에 모두 지정한다 — 한쪽만 지정하면 토큰 오류가 기본 응답(빈 본문)으로 나간다</li>
 * </ul>
 * <p>
 * <b>CSRF 보호를 끄는 근거</b>: 세션 쿠키가 SameSite=Strict 라 다른 사이트에서 시작한 요청에는 실리지 않고, 프런트는 같은 출처(프록시)로
 * 배포하며, 상태를 바꾸는 API 는 대부분 JSON 본문(@RequestBody, application/json)을 받거나 PATCH·PUT 이다 — 폼 전송(GET·POST 만
 * 가능)으로는 만들 수 없는 요청이다. 본문 없는 POST(직원 비밀번호 초기화)는 폼으로 보낼 수 있지만 SameSite=Strict 쿠키가 실리지 않아
 * 401 이 되고, 응답(초기 비밀번호)은 다른 출처에서 읽을 수 없다.
 * 켜 두면 CSRF 토큰이 없는 로그인 POST 부터 403 이 된다.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableConfigurationProperties({ CorsProperties.class, LoginRateLimitProperties.class, OriginGuardProperties.class })
public class SecurityConfig {

	/** 로그인 없이 부를 수 있는 경로. 이 경로에서는 쿠키의 토큰을 읽지도 않는다 ({@link CookieBearerTokenResolver}). */
	static final RequestMatcher PUBLIC_ENDPOINTS = new OrRequestMatcher(
			pathPattern(HttpMethod.POST, "/api/auth/login"),
			pathPattern(HttpMethod.POST, "/api/auth/logout"),
			// 매장 개설 (REQ-29) — 계정이 하나도 없을 때만 열리므로 로그인 전에 부른다. 계정이 있으면 서비스가 403 으로 막는다
			pathPattern(HttpMethod.GET, "/api/setup/status"),
			pathPattern(HttpMethod.POST, "/api/setup"),
			pathPattern(HttpMethod.GET, "/actuator/health"),
			pathPattern("/error"));

	/**
	 * 비밀번호를 바꿔야 하는 계정도 쓸 수 있는 경로 (REQ-30). 로그인만 확인하고 역할은 보지 않는다 — 세션 조회는 화면이 변경 필요 여부를
	 * 알아야 해서, 비밀번호 변경은 그 계정이 할 수 있는 유일한 일이라서 연다. 로그아웃은 공개 경로라 따로 적지 않는다.
	 */
	static final RequestMatcher PASSWORD_CHANGE_ALLOWED_ENDPOINTS = new OrRequestMatcher(
			pathPattern(HttpMethod.GET, "/api/auth/session"),
			pathPattern(HttpMethod.PUT, "/api/auth/password"));

	/** 사장(ADMIN)만 부를 수 있는 경로 (REQ-16·30). 그 밖의 로그인 필요 경로는 ADMIN·STAFF 모두 쓴다. */
	static final RequestMatcher ADMIN_ENDPOINTS = new OrRequestMatcher(
			pathPattern(HttpMethod.PATCH, "/api/customers/{id}/deactivate"),
			// 직원 계정 관리 (REQ-30) — 목록·추가·비밀번호 초기화·비활성화 모두. 방식을 정하지 않아 새 경로도 자동으로 ADMIN 전용이다
			pathPattern("/api/employees/**"));

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, EmployeeJwtAuthenticationConverter authenticationConverter,
			RestAuthenticationEntryPoint authenticationEntryPoint, RestAccessDeniedHandler accessDeniedHandler,
			CorsProperties corsProperties, LoginRateLimitProperties loginRateLimitProperties, Clock clock,
			SecurityErrorResponseWriter errorResponseWriter, OriginGuardProperties originGuardProperties) throws Exception {
		http
				.csrf(AbstractHttpConfigurer::disable)
				.formLogin(AbstractHttpConfigurer::disable)
				.httpBasic(AbstractHttpConfigurer::disable)
				.logout(AbstractHttpConfigurer::disable)
				.requestCache(AbstractHttpConfigurer::disable)
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(authorize -> authorize
						.requestMatchers(PUBLIC_ENDPOINTS).permitAll()
						.requestMatchers(PASSWORD_CHANGE_ALLOWED_ENDPOINTS).authenticated()
						.requestMatchers(ADMIN_ENDPOINTS).hasRole("ADMIN")
						.anyRequest().hasAnyRole("ADMIN", "STAFF"))
				.exceptionHandling(exceptions -> exceptions
						.authenticationEntryPoint(authenticationEntryPoint)
						.accessDeniedHandler(accessDeniedHandler))
				.oauth2ResourceServer(resourceServer -> resourceServer
						.bearerTokenResolver(new CookieBearerTokenResolver(PUBLIC_ENDPOINTS))
						.authenticationEntryPoint(authenticationEntryPoint)
						.accessDeniedHandler(accessDeniedHandler)
						.jwt(jwt -> jwt.jwtAuthenticationConverter(authenticationConverter)));
		// 프록시를 거치지 않은 요청 거절 (CB-59). 다른 필터보다 먼저 등록해 레이트 리밋·인증보다 앞에서 거절한다. 설정값이 비면 넣지 않는다
		if (originGuardProperties.isEnabled()) {
			http.addFilterBefore(new OriginGuardFilter(originGuardProperties, errorResponseWriter), LogoutFilter.class);
		}
		// DSL 이 자동으로 여는 메타데이터 경로를 막는다. 메타데이터 필터(인증 필터 바로 앞)보다 확실히 앞에 두려고 LogoutFilter 자리를 기준으로 한다
		http.addFilterBefore(new ProtectedResourceMetadataBlockingFilter(authenticationEntryPoint), LogoutFilter.class);
		// 로그인 요청 수 제한 (REQ-15). CorsFilter·HeaderWriterFilter 뒤라 429 에도 CORS·보안 헤더가 붙고, 본문을 읽거나 DB 를 조회하기 전에 거절한다.
		// 체인 빈이 하나라 필터 인스턴스(IP 별 횟수)도 하나다 — 서블릿 필터로 따로 등록되지 않게 빈으로 만들지 않는다
		http.addFilterBefore(new LoginRateLimitFilter(loginRateLimitProperties, clock, errorResponseWriter), LogoutFilter.class);
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
		configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "OPTIONS"));
		configuration.setAllowedHeaders(List.of("Content-Type", "Idempotency-Key"));
		configuration.setAllowCredentials(true);
		configuration.setMaxAge(3600L);
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/api/**", configuration);
		return source;
	}

}

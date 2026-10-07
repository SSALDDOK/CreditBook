package com.creditbook.auth.controller;

import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.creditbook.auth.application.AuthService;
import com.creditbook.auth.application.AuthenticatedEmployee;
import com.creditbook.auth.application.LoginResult;
import com.creditbook.auth.controller.dto.LoginRequest;
import com.creditbook.auth.controller.dto.SessionResponse;
import com.creditbook.global.security.SessionCookies;
import com.creditbook.global.security.UnauthenticatedException;

import jakarta.validation.Valid;

/**
 * 직원 로그인·로그아웃·세션 조회 API (REQ-15·22). 토큰은 HttpOnly 쿠키로만 주고받고 응답 본문에는 싣지 않는다.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

	private final AuthService authService;

	public AuthController(AuthService authService) {
		this.authService = authService;
	}

	/** 로그인. 성공하면 세션 쿠키를 심고 직원 정보와 만료 시각을 돌려준다. 실패는 사유와 관계없이 401 INVALID_CREDENTIALS. */
	@PostMapping("/login")
	public ResponseEntity<SessionResponse> login(@Valid @RequestBody LoginRequest request) {
		LoginResult result = authService.login(request.loginId(), request.password());
		return ResponseEntity.ok()
				.header(HttpHeaders.SET_COOKIE,
						SessionCookies.issue(result.token().value(), result.token().ttl()).toString())
				.body(SessionResponse.of(result.employee(), result.token().expiresAt()));
	}

	/** 로그아웃. 쿠키의 토큰이 유효하면 폐기하고, 없거나 유효하지 않아도 항상 204 와 쿠키 삭제로 응답한다. */
	@PostMapping("/logout")
	public ResponseEntity<Void> logout(
			@CookieValue(name = SessionCookies.NAME, required = false) String tokenValue) {
		authService.logout(tokenValue);
		return ResponseEntity.noContent()
				.header(HttpHeaders.SET_COOKIE, SessionCookies.clear().toString())
				.build();
	}

	/** 현재 세션의 직원 정보. 화면이 새로고침 후 로그인 상태를 복원할 때 쓴다. */
	@GetMapping("/session")
	public SessionResponse session(@AuthenticationPrincipal Jwt jwt) {
		if (jwt == null) {
			throw new UnauthenticatedException();
		}
		AuthenticatedEmployee employee = authService.session(UUID.fromString(jwt.getSubject()));
		return SessionResponse.of(employee, jwt.getExpiresAt());
	}

}

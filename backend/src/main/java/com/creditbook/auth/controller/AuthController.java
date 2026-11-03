package com.creditbook.auth.controller;

import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.creditbook.auth.application.AuthService;
import com.creditbook.auth.application.AuthenticatedEmployee;
import com.creditbook.auth.application.LoginResult;
import com.creditbook.auth.controller.dto.ChangePasswordRequest;
import com.creditbook.auth.controller.dto.LoginRequest;
import com.creditbook.auth.controller.dto.SessionResponse;
import com.creditbook.global.security.SessionCookies;
import com.creditbook.global.security.UnauthenticatedException;

import jakarta.validation.Valid;

/**
 * 직원 로그인·로그아웃·세션 조회·비밀번호 변경 API (REQ-15·22·30). 토큰은 HttpOnly 쿠키로만 주고받고 응답 본문에는 싣지 않는다.
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

	/**
	 * 로그인한 직원의 비밀번호 변경 (REQ-30). 첫 로그인 강제 변경과 스스로 바꾸기에 같이 쓴다 — 비밀번호를 바꿔야 하는 계정도 부를 수 있다.
	 * 응답은 세션 조회와 같은 모양이고 만료 시각은 지금 토큰의 만료 시각이다 (토큰을 새로 발급하지 않는다).
	 * 현재 비밀번호가 틀리면 400 INVALID_CURRENT_PASSWORD(로그인 실패와 같은 횟수로 센다, 기준 횟수째면 423 ACCOUNT_LOCKED),
	 * 새 비밀번호가 현재와 같으면 400 PASSWORD_UNCHANGED.
	 */
	@PutMapping("/password")
	public SessionResponse changePassword(@AuthenticationPrincipal Jwt jwt,
			@Valid @RequestBody ChangePasswordRequest request) {
		if (jwt == null) {
			throw new UnauthenticatedException();
		}
		AuthenticatedEmployee employee = authService.changePassword(UUID.fromString(jwt.getSubject()),
				request.currentPassword(), request.newPassword());
		return SessionResponse.of(employee, jwt.getExpiresAt());
	}

}

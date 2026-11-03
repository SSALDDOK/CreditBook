package com.creditbook.global.security;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import com.creditbook.global.error.ErrorCode;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 로그인했지만 권한이 없는 요청(예: STAFF 의 ADMIN 전용 기능)을 403 FORBIDDEN 으로 거절한다 (REQ-16).
 * 비밀번호를 바꿔야 하는 계정(REQ-30)이면 403 PASSWORD_CHANGE_REQUIRED 로 거절한다 — 화면이 비밀번호 변경 화면으로 보낼 수 있게.
 * 정상적인 거절이므로 WARN — 직원 ID 와 요청 방식·경로만 남긴다.
 */
@Component
class RestAccessDeniedHandler implements AccessDeniedHandler {

	private static final Logger log = LoggerFactory.getLogger(RestAccessDeniedHandler.class);

	private final SecurityErrorResponseWriter writer;

	RestAccessDeniedHandler(SecurityErrorResponseWriter writer) {
		this.writer = writer;
	}

	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response,
			AccessDeniedException accessDeniedException) throws IOException {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (isPasswordChangeRequired(authentication)) {
			log.warn("access denied: employeeId={} reason=password change required method={} path={}",
					authentication.getName(), request.getMethod(), request.getRequestURI());
			writer.write(response, ErrorCode.PASSWORD_CHANGE_REQUIRED);
			return;
		}
		log.warn("access denied: employeeId={} method={} path={}",
				authentication == null ? null : authentication.getName(), request.getMethod(), request.getRequestURI());
		writer.write(response, ErrorCode.FORBIDDEN);
	}

	private static boolean isPasswordChangeRequired(Authentication authentication) {
		return authentication != null && authentication.getAuthorities().stream()
				.anyMatch(granted -> EmployeeJwtAuthenticationConverter.PASSWORD_CHANGE_REQUIRED_AUTHORITY
						.equals(granted.getAuthority()));
	}

}

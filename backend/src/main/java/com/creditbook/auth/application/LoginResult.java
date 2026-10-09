package com.creditbook.auth.application;

import com.creditbook.global.security.JwtTokenService.IssuedToken;

/**
 * 로그인 결과. 토큰은 컨트롤러가 쿠키에만 싣고 응답 본문에는 싣지 않는다.
 */
public record LoginResult(AuthenticatedEmployee employee, IssuedToken token) {
}

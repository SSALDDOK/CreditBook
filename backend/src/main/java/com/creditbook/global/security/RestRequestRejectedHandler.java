package com.creditbook.global.security;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.security.web.firewall.RequestRejectedHandler;
import org.springframework.stereotype.Component;

import com.creditbook.global.error.ErrorCode;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 보안 필터의 경로 검사(HttpFirewall)가 거절한 요청 — 주소에 // 나 정리되지 않은 .. 등 — 을
 * 다른 오류와 같은 표준 형식 400 INVALID_REQUEST_PATH 로 응답한다 (REQ-27, BUG-5).
 * <p>
 * 이 처리가 없으면 기본 처리가 상태 코드만 보내고, 서블릿 컨테이너가 그 오류를 프레임워크 기본 오류 형식
 * (timestamp·status·error·path)으로 바꿔 내보낸다. 인증보다 앞 단계라 로그인 여부와 관계없다.
 * 스프링 시큐리티가 이 타입의 빈을 찾아 필터 체인에 쓴다.
 * <p>
 * 의심스러운 요청이지만 정상적인 거절이므로 WARN. 주소 원문은 남기지 않는다 — 공격 문자열이 그대로 로그에 실리지 않게.
 */
@Component
class RestRequestRejectedHandler implements RequestRejectedHandler {

	private static final Logger log = LoggerFactory.getLogger(RestRequestRejectedHandler.class);

	private final SecurityErrorResponseWriter writer;

	RestRequestRejectedHandler(SecurityErrorResponseWriter writer) {
		this.writer = writer;
	}

	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response,
			RequestRejectedException requestRejectedException) throws IOException {
		log.warn("request path rejected: method={}", request.getMethod());
		writer.write(response, ErrorCode.INVALID_REQUEST_PATH);
	}

}

package com.creditbook.global.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import com.creditbook.global.error.ErrorCode;
import com.creditbook.global.error.ErrorResponse;

import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/**
 * 보안 필터에서 거절한 요청에도 컨트롤러 오류와 같은 {@link ErrorResponse} 본문을 쓴다.
 * 필터 단계의 거절은 {@code GlobalExceptionHandler} 를 거치지 않기 때문이다.
 */
@Component
class SecurityErrorResponseWriter {

	private final JsonMapper jsonMapper;

	SecurityErrorResponseWriter(JsonMapper jsonMapper) {
		this.jsonMapper = jsonMapper;
	}

	void write(HttpServletResponse response, ErrorCode code) throws IOException {
		response.setStatus(code.status().value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.getWriter().write(jsonMapper.writeValueAsString(ErrorResponse.of(code)));
	}

}

package com.creditbook.global.logging;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 요청 correlation id 를 정해 그 요청의 모든 로그 라인(MDC {@code requestId})과 응답 헤더 {@code X-Request-Id} 에 싣는다.
 * <p>
 * 들어온 {@code X-Request-Id} 는 {@code ^[A-Za-z0-9-]{1,64}$} 에 맞을 때만 그대로 쓰고, 비었거나 너무 길거나
 * 그 밖의 문자(공백·개행·{@code %}·중괄호 등)가 있으면 버리고 새 UUID 를 만든다 — 클라이언트 값이 로그 줄을
 * 위조하거나 패턴을 깨뜨리지 못하게 하려는 허용 목록 검증이다.
 * <p>
 * 응답 헤더는 체인을 부르기 전에 넣는다. 그래서 컨트롤러·예외 처리기·보안 필터가 만든 4xx·5xx 응답에도 같은 값이 실린다.
 * 같은 요청이 ERROR 디스패치로 다시 들어오면 요청 속성에 남긴 값을 다시 쓴다(새 id 를 만들지 않는다).
 */
// 순서 보장: Spring Security 필터 체인(DelegatingFilterProxy, 기본 order -100)보다 먼저 돌도록 가장 높은 우선순위로 등록한다 — 401·403 로그에도 id 가 찍힌다
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

	public static final String HEADER_NAME = "X-Request-Id";
	public static final String MDC_KEY = "requestId";
	static final String REQUEST_ATTRIBUTE = RequestIdFilter.class.getName() + ".requestId";

	private static final Pattern ALLOWED = Pattern.compile("^[A-Za-z0-9-]{1,64}$");

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		String requestId = resolveRequestId(request);
		request.setAttribute(REQUEST_ATTRIBUTE, requestId);
		response.setHeader(HEADER_NAME, requestId);

		String previous = MDC.get(MDC_KEY);
		MDC.put(MDC_KEY, requestId);
		try {
			filterChain.doFilter(request, response);
		}
		finally {
			// 스레드가 재사용될 때 다음 요청에 id 가 남지 않게 한다. 같은 스레드에서 겹쳐 들어온 경우만 바깥 값을 되돌린다
			if (previous == null) {
				MDC.remove(MDC_KEY);
			}
			else {
				MDC.put(MDC_KEY, previous);
			}
		}
	}

	/** ERROR 디스패치(컨테이너 오류 페이지)에서도 id 를 다시 싣는다. 값은 처음 요청 때 정한 것을 쓴다. */
	@Override
	protected boolean shouldNotFilterErrorDispatch() {
		return false;
	}

	static boolean isAcceptable(String candidate) {
		return candidate != null && ALLOWED.matcher(candidate).matches();
	}

	private static String resolveRequestId(HttpServletRequest request) {
		if (request.getAttribute(REQUEST_ATTRIBUTE) instanceof String existing) {
			return existing;
		}
		String incoming = request.getHeader(HEADER_NAME);
		return isAcceptable(incoming) ? incoming : UUID.randomUUID().toString();
	}

}

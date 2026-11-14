package com.creditbook.global.security;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import com.creditbook.global.error.ErrorCode;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * IP 별 요청 수 제한. {@code matcher} 에 맞는 요청만 센다 — 성공·실패와 관계없이 같은 IP 의 모든 요청을 고정 창({@code window})마다
 * {@code maxRequests} 번까지 받고, 넘으면 본문을 읽거나 DB 를 조회하기 전에 {@code errorCode}(429)와 {@code Retry-After}(창이 끝날 때까지
 * 남은 초, 올림)로 거절한다 (details 없음). 경로마다 인스턴스를 하나씩 두고, 횟수는 인스턴스마다 따로 센다.
 * <ul>
 * <li>로그인 (REQ-15, CB-15): POST /api/auth/login, 429 LOGIN_RATE_LIMITED. 계정 잠금(없는 아이디는 세지 않음)이 못 막는 여러 아이디 대입을 막는다</li>
 * <li>초대 코드 가입 (REQ-36): POST /api/setup, 429 SIGNUP_RATE_LIMITED. 초대 코드 대입을 막는다</li>
 * </ul>
 * <p>
 * 클라이언트 IP 는 {@link HttpServletRequest#getRemoteAddr()} 만 쓴다. 프록시 뒤에서는 {@code server.forward-headers-strategy: native}
 * (Tomcat RemoteIpValve)가 내부 프록시가 붙인 X-Forwarded-For 만 믿고 이 값을 바꿔 준다 — 여기서 X-Forwarded-For 를 직접 읽으면
 * 클라이언트가 헤더를 바꿔 제한을 피할 수 있다.
 * <p>
 * 횟수는 메모리에만 둔다(서버 1대 전제, 재시작하면 초기화). 창이 끝난 항목은 창 길이마다 한 번 지워 맵이 계속 커지지 않게 한다.
 * 로그는 IP 와 창마다 처음 걸린 때 한 줄(WARN, "{name} rate limited: …")만 남긴다 — 본문을 읽지 않으므로 아이디·비밀번호·초대 코드는
 * 남길 수도 없다.
 * <p>
 * 보안 필터 체인 안에 둔다. 체인은 {@code RequestIdFilter} 뒤에 돌므로 거절 로그에도 요청 id 가 찍힌다.
 */
final class IpRateLimitFilter extends OncePerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger(IpRateLimitFilter.class);

	private final String name;
	private final RequestMatcher matcher;
	private final int maxRequests;
	private final Duration window;
	private final ErrorCode errorCode;
	private final Clock clock;
	private final SecurityErrorResponseWriter errorResponseWriter;
	private final Map<String, Window> windows = new ConcurrentHashMap<>();
	private volatile Instant nextSweepAt;

	/**
	 * @param name 로그에 쓰는 이름 ("login" → "login rate limited: …")
	 * @param matcher 셀 요청
	 * @param maxRequests 창 하나에서 받는 요청 수 (1 이상)
	 * @param window 창 길이 (0보다 큼)
	 * @param errorCode 넘었을 때 응답할 오류 코드 (429)
	 */
	IpRateLimitFilter(String name, RequestMatcher matcher, int maxRequests, Duration window, ErrorCode errorCode, Clock clock,
			SecurityErrorResponseWriter errorResponseWriter) {
		this.name = Objects.requireNonNull(name, "name");
		this.matcher = Objects.requireNonNull(matcher, "matcher");
		if (maxRequests < 1) {
			throw new IllegalArgumentException("maxRequests must be at least 1");
		}
		this.maxRequests = maxRequests;
		this.window = Objects.requireNonNull(window, "window");
		if (window.isNegative() || window.isZero()) {
			throw new IllegalArgumentException("window must be positive");
		}
		this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
		this.clock = Objects.requireNonNull(clock, "clock");
		this.errorResponseWriter = Objects.requireNonNull(errorResponseWriter, "errorResponseWriter");
		this.nextSweepAt = clock.instant().plus(window);
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !matcher.matches(request);
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		Instant now = clock.instant();
		sweepExpired(now);
		String clientIp = request.getRemoteAddr();
		Window current = windows.compute(clientIp,
				(ip, previous) -> previous == null || previous.hasEnded(now) ? new Window(now.plus(window), 1) : previous.next());

		if (current.count() > maxRequests) {
			if (current.count() == maxRequests + 1) {
				log.warn("{} rate limited: ip={} maxRequests={} window={}", name, clientIp, maxRequests, window);
			}
			response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds(now, current.endsAt())));
			errorResponseWriter.write(response, errorCode);
			return;
		}
		filterChain.doFilter(request, response);
	}

	/** 지금 맵에 남은 IP 수 (테스트용). */
	int trackedClients() {
		return windows.size();
	}

	private void sweepExpired(Instant now) {
		if (now.isBefore(nextSweepAt)) {
			return;
		}
		nextSweepAt = now.plus(window);
		windows.entrySet().removeIf(entry -> entry.getValue().hasEnded(now));
	}

	/** 창이 끝날 때까지 남은 초, 올림. 그 시각에 다시 보내면 새 창으로 받아진다. */
	static long retryAfterSeconds(Instant now, Instant endsAt) {
		Duration remaining = Duration.between(now, endsAt);
		long seconds = remaining.getSeconds() + (remaining.getNano() > 0 ? 1 : 0);
		return Math.max(seconds, 1);
	}

	/** IP 하나의 현재 창. 끝 시각과 그 창에서 받은 요청 수. */
	private record Window(Instant endsAt, int count) {

		boolean hasEnded(Instant now) {
			return !now.isBefore(endsAt);
		}

		/** 요청 하나를 더 센다. 넘친 뒤에도 계속 세지만 int 범위에서 멈춘다. */
		Window next() {
			return count == Integer.MAX_VALUE ? this : new Window(endsAt, count + 1);
		}

	}

}

package com.creditbook.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;

import com.creditbook.global.security.JwtTestSupport.MutableClock;
import com.creditbook.global.security.JwtTokenService.IssuedToken;
import com.creditbook.global.security.JwtTokenService.VerifiedToken;

/**
 * 토큰 발급·검증 (스프링 없이 운영과 같은 Nimbus 발급기·검증기). 키는 실행마다 무작위다.
 */
@Tag("REQ-15")
@Tag("REQ-22")
class JwtTokenServiceTest {

	private static final Instant NOW = Instant.parse("2026-10-07T14:30:00.750Z");
	private static final UUID EMPLOYEE_ID = UUID.fromString("7d0c2f4e-1a2b-4c3d-8e9f-0a1b2c3d4e5f");

	private AuthProperties properties;
	private MutableClock clock;
	private JwtTokenService tokenService;
	private JwtDecoder decoder;

	@BeforeEach
	void setUp() {
		properties = JwtTestSupport.randomProperties();
		clock = new MutableClock(NOW);
		tokenService = JwtTestSupport.tokenService(properties, clock);
		decoder = JwtTestSupport.decoder(properties, clock);
	}

	@Test
	@DisplayName("발급한 토큰에는 직원 ID(sub)·토큰 ID(jti)·발급·만료 시각만 있고 역할은 없다")
	void issued_token_has_only_sub_jti_iat_exp() {
		// when
		IssuedToken token = tokenService.issue(EMPLOYEE_ID);

		// then
		Jwt jwt = decoder.decode(token.value());
		assertThat(jwt.getSubject()).isEqualTo(EMPLOYEE_ID.toString());
		assertThat(UUID.fromString(jwt.getId())).isNotNull();
		assertThat(jwt.getClaims()).containsOnlyKeys("sub", "jti", "iat", "exp");
		assertThat(jwt.getHeaders()).containsEntry("alg", "HS256");
	}

	@Test
	@DisplayName("토큰은 발급 시각(초 단위)부터 16시간 동안 유효하고 응답의 만료 시각은 토큰의 exp 와 같다")
	void token_expires_16_hours_after_issue() {
		// when
		IssuedToken token = tokenService.issue(EMPLOYEE_ID);

		// then
		Jwt jwt = decoder.decode(token.value());
		Instant issuedAt = Instant.parse("2026-10-07T14:30:00Z");
		assertThat(jwt.getIssuedAt()).isEqualTo(issuedAt);
		assertThat(jwt.getExpiresAt()).isEqualTo(issuedAt.plus(JwtTestSupport.TTL));
		assertThat(token.expiresAt()).isEqualTo(jwt.getExpiresAt());
		assertThat(token.ttl()).isEqualTo(JwtTestSupport.TTL);
	}

	@Test
	@DisplayName("토큰을 발급할 때마다 토큰 ID(jti)가 달라진다")
	void each_token_has_a_new_jti() {
		// when
		IssuedToken first = tokenService.issue(EMPLOYEE_ID);
		IssuedToken second = tokenService.issue(EMPLOYEE_ID);

		// then
		assertThat(decoder.decode(first.value()).getId()).isNotEqualTo(decoder.decode(second.value()).getId());
	}

	@Test
	@DisplayName("만료 시각까지는 유효하고, 만료 시각에서 1초만 지나도 거절한다 (시계 오차 허용 0)")
	void token_is_rejected_one_second_after_expiry() {
		// given
		IssuedToken token = tokenService.issue(EMPLOYEE_ID);

		// when: 만료 시각 정각
		clock.set(token.expiresAt());

		// then
		assertThat(tokenService.verify(token.value())).isPresent();

		// when: 만료 1초 후
		clock.set(token.expiresAt().plusSeconds(1));

		// then
		assertThat(tokenService.verify(token.value())).isEmpty();
		assertThatThrownBy(() -> decoder.decode(token.value())).isInstanceOf(JwtException.class);
	}

	@Test
	@DisplayName("유효한 토큰을 해석하면 직원 ID·토큰 ID·만료 시각을 얻는다")
	void verify_returns_token_contents() {
		// given
		IssuedToken token = tokenService.issue(EMPLOYEE_ID);
		String jti = decoder.decode(token.value()).getId();

		// when
		Optional<VerifiedToken> verified = tokenService.verify(token.value());

		// then
		assertThat(verified).contains(new VerifiedToken(EMPLOYEE_ID, UUID.fromString(jti), token.expiresAt()));
	}

	@Test
	@DisplayName("서명을 바꾼 토큰은 거절한다")
	void tampered_signature_is_rejected() {
		// given
		String value = tokenService.issue(EMPLOYEE_ID).value();
		char last = value.charAt(value.length() - 1);
		String tampered = value.substring(0, value.length() - 1) + (last == 'A' ? 'B' : 'A');

		// when / then
		assertThat(tokenService.verify(tampered)).isEmpty();
	}

	@Test
	@DisplayName("내용(payload)을 바꾼 토큰은 거절한다")
	void tampered_payload_is_rejected() {
		// given: 다른 직원의 토큰 payload 를 붙인다
		String mine = tokenService.issue(EMPLOYEE_ID).value();
		String others = tokenService.issue(UUID.randomUUID()).value();
		String[] a = mine.split("\\.");
		String[] b = others.split("\\.");
		String forged = a[0] + "." + b[1] + "." + a[2];

		// when / then
		assertThat(tokenService.verify(forged)).isEmpty();
	}

	@Test
	@DisplayName("다른 키로 서명한 토큰은 거절한다")
	void token_signed_with_other_key_is_rejected() {
		// given
		JwtTokenService otherServer = JwtTestSupport.tokenService(JwtTestSupport.randomProperties(), clock);
		String foreign = otherServer.issue(EMPLOYEE_ID).value();

		// when / then
		assertThat(tokenService.verify(foreign)).isEmpty();
	}

	@Test
	@DisplayName("서명 없는(alg=none) 토큰은 거절한다")
	void unsigned_token_is_rejected() {
		// given
		String[] parts = tokenService.issue(EMPLOYEE_ID).value().split("\\.");
		String header = java.util.Base64.getUrlEncoder().withoutPadding()
				.encodeToString("{\"alg\":\"none\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
		String unsigned = header + "." + parts[1] + ".";

		// when / then
		assertThat(tokenService.verify(unsigned)).isEmpty();
	}

	@ParameterizedTest(name = "[{index}] \"{0}\"")
	@ValueSource(strings = { "", " ", "abc", "a.b.c", "not-a-jwt-at-all" })
	@DisplayName("형식이 깨진 토큰은 예외 없이 거절한다")
	void malformed_token_is_rejected_without_exception(String value) {
		// when / then
		assertThat(tokenService.verify(value)).isEmpty();
	}

	@Test
	@DisplayName("토큰이 없으면 거절한다")
	void null_token_is_rejected() {
		assertThat(tokenService.verify(null)).isEmpty();
	}

	@Test
	@DisplayName("sub 가 UUID 가 아닌 토큰은 해석 단계에서 예외 없이 거절한다")
	void token_with_non_uuid_sub_is_rejected_without_exception() {
		// given
		String value = encode(JwtClaimsSet.builder()
				.subject("not-a-uuid").id(UUID.randomUUID().toString())
				.issuedAt(NOW).expiresAt(NOW.plusSeconds(60)).build());

		// when / then
		assertThat(tokenService.verify(value)).isEmpty();
	}

	@ParameterizedTest(name = "[{index}] {0} 없음")
	@ValueSource(strings = { "sub", "jti", "exp" })
	@DisplayName("sub·jti·exp 중 하나라도 없는 토큰은 거절한다")
	void token_without_required_claim_is_rejected(String missing) {
		// given
		JwtClaimsSet.Builder claims = JwtClaimsSet.builder().issuedAt(NOW);
		if (!missing.equals("sub")) {
			claims.subject(EMPLOYEE_ID.toString());
		}
		if (!missing.equals("jti")) {
			claims.id(UUID.randomUUID().toString());
		}
		if (!missing.equals("exp")) {
			claims.expiresAt(NOW.plusSeconds(60));
		}

		// when
		String value = encode(claims.build());

		// then
		assertThatThrownBy(() -> decoder.decode(value)).isInstanceOf(JwtException.class);
		assertThat(tokenService.verify(value)).isEmpty();
	}

	@Test
	@DisplayName("발급 결과의 문자열 표현에는 토큰 원문이 없다")
	void issued_token_to_string_hides_value() {
		IssuedToken token = tokenService.issue(EMPLOYEE_ID);
		assertThat(token.toString()).doesNotContain(token.value());
	}

	private String encode(JwtClaimsSet claims) {
		return JwtTestSupport.encoder(properties)
				.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
				.getTokenValue();
	}

}

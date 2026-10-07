package com.creditbook.global.security;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * JWT(HS256) 발급기·검증기. 키는 {@link AuthProperties} 에서만 온다.
 * <p>
 * 검증기는 서명·알고리즘(HS256 고정)과 아래 기본 검증만 한다. 직원 활성 여부·로그아웃 폐기 여부는 DB 를 봐야 하므로
 * {@link EmployeeJwtAuthenticationConverter} 가 같은 요청에서 한 번의 조회로 확인한다.
 * <ul>
 * <li>exp 필수, 시계 오차 허용 0 — 만료 시각이 지나면 바로 거절한다. 시각은 서버 Clock 빈 기준</li>
 * <li>sub·jti 필수</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthProperties.class)
public class JwtConfig {

	@Bean
	JwtEncoder jwtEncoder(AuthProperties properties) {
		return NimbusJwtEncoder.withSecretKey(secretKey(properties)).algorithm(MacAlgorithm.HS256).build();
	}

	@Bean
	JwtDecoder jwtDecoder(AuthProperties properties, Clock clock) {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(secretKey(properties))
				.macAlgorithm(MacAlgorithm.HS256)
				.build();
		JwtTimestampValidator timestamps = new JwtTimestampValidator(Duration.ZERO);
		timestamps.setClock(clock);
		timestamps.setAllowEmptyExpiryClaim(false);
		decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<Jwt>(
				timestamps,
				new JwtClaimValidator<Object>(JwtClaimNames.SUB, Objects::nonNull),
				new JwtClaimValidator<Object>(JwtClaimNames.JTI, Objects::nonNull)));
		return decoder;
	}

	private static SecretKey secretKey(AuthProperties properties) {
		return new SecretKeySpec(properties.jwtSecretBytes(), "HmacSHA256");
	}

}

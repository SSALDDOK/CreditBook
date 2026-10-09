package com.creditbook.support;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * 테스트 전용: 스프링 컨텍스트를 띄울 때마다 JWT 서명 키를 무작위로 만들어 넣는다 (src/test/resources/META-INF/spring.factories 로 등록).
 * <p>
 * 그래서 테스트·CI 에는 CREDITBOOK_JWT_SECRET 이 필요 없고, 리포에 고정 키 문자열을 두지 않는다.
 * 가장 높은 우선순위로 넣으므로 개발자 PC 에 환경변수가 있어도 테스트는 실제 키를 쓰지 않는다.
 * 테스트 클래스패스에만 있으므로 운영 기동에는 영향이 없다.
 */
public class RandomJwtSecretEnvironmentPostProcessor implements EnvironmentPostProcessor {

	static final String PROPERTY = "creditbook.auth.jwt-secret";

	private static final SecureRandom RANDOM = new SecureRandom();

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		byte[] bytes = new byte[48];
		RANDOM.nextBytes(bytes);
		String secret = Base64.getEncoder().encodeToString(bytes); // 64자 = 64바이트
		environment.getPropertySources().addFirst(new MapPropertySource("testRandomJwtSecret", Map.of(PROPERTY, secret)));
	}

}

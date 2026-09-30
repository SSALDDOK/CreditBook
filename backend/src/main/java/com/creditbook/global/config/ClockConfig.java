package com.creditbook.global.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 서버 시각의 단일 출처. 서비스는 Instant.now() 를 직접 부르지 않고 이 Clock 을 주입받는다 (REQ-20, 테스트 재현성).
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}

}

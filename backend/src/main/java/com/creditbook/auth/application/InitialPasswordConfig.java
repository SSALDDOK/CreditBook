package com.creditbook.auth.application;

import java.security.SecureRandom;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.creditbook.auth.domain.InitialPasswordGenerator;

/** 초기 비밀번호 생성기. 운영은 SecureRandom 을 쓴다 — 테스트는 시드를 고정한 생성기로 도메인 객체를 직접 만든다. */
@Configuration(proxyBeanMethods = false)
class InitialPasswordConfig {

	@Bean
	InitialPasswordGenerator initialPasswordGenerator() {
		return new InitialPasswordGenerator(new SecureRandom());
	}

}

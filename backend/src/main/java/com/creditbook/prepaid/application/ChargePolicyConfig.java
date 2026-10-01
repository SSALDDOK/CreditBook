package com.creditbook.prepaid.application;

import java.math.BigDecimal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.creditbook.prepaid.domain.ChargePolicy;

/**
 * 충전 정책을 설정값에서 만든다. 도메인({@link ChargePolicy})은 설정을 직접 읽지 않는다.
 * 값이 없거나 1원 이상의 정수가 아니면 기동 시점에 실패한다.
 */
@Configuration(proxyBeanMethods = false)
class ChargePolicyConfig {

	@Bean
	ChargePolicy chargePolicy(@Value("${creditbook.charge.max-amount}") BigDecimal maxAmount) {
		return new ChargePolicy(maxAmount);
	}

}

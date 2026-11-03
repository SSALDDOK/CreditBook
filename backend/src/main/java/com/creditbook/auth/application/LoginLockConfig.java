package com.creditbook.auth.application;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 로그인 잠금 기준({@link LoginLockProperties})을 설정값에서 읽는다. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LoginLockProperties.class)
class LoginLockConfig {

}

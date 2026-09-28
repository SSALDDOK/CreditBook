package com.creditbook.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Testcontainers 가 필요한 테스트에 붙인다.
 * 로컬에 Docker 가 없으면 건너뛰지만, CI(환경변수 CI 설정)에서 Docker 가 없으면 실패시킨다 —
 * 정합성 테스트가 CI 에서 조용히 스킵된 채 초록불이 되는 것을 막기 위해서다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(DockerAvailableCondition.class)
public @interface RequiresDocker {
}

package com.creditbook.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.security.test.context.support.WithSecurityContext;

/**
 * 테스트를 로그인한 직원으로 실행한다. 실제 로그인과 같은 형태(JwtAuthenticationToken, 주체 = 직원 ID, 권한 = ROLE_역할)의 인증을
 * SecurityContext 에 넣는다. 토큰 검증·DB 조회는 거치지 않으므로 웹 계층 테스트에서 인증만 주입할 때 쓴다.
 * 클래스나 메서드에 붙인다.
 */
@Target({ ElementType.TYPE, ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
@WithSecurityContext(factory = WithMockEmployeeSecurityContextFactory.class)
public @interface WithMockEmployee {

	/** 직원 ID (토큰의 sub). */
	String id() default "00000000-0000-0000-0000-00000000e001";

	/** 역할: ADMIN 또는 STAFF. */
	String role() default "STAFF";

}

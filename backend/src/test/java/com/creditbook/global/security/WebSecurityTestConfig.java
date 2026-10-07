package com.creditbook.global.security;

import java.util.Optional;
import java.util.UUID;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import com.creditbook.auth.domain.Employee;
import com.creditbook.auth.domain.EmployeeAuthStatus;
import com.creditbook.auth.domain.EmployeeRepository;
import com.creditbook.global.config.ClockConfig;

/**
 * {@code @WebMvcTest} 에 운영과 같은 보안 구성(SecurityConfig·JWT 검증·401/403 처리기)을 올린다.
 * 슬라이스는 @Configuration·@Component 를 스캔하지 않으므로 여기서 가져온다. 서명 키는 실행마다 무작위다
 * (RandomJwtSecretEnvironmentPostProcessor).
 * <p>
 * DB 가 없으므로 직원 저장소는 "직원 없음" 스텁이다 — 쿠키 토큰으로 인증을 확인하는 테스트는 {@code @MockitoBean EmployeeRepository} 로 바꾼다.
 * 인증만 필요하면 {@link com.creditbook.support.WithMockEmployee} 를 쓴다. CurrentEmployee 구현은 넣지 않는다
 * (기존 테스트가 목으로 넣거나, 빈이 없는 경우를 검증한다).
 */
@TestConfiguration(proxyBeanMethods = false)
@Import({ SecurityConfig.class, JwtConfig.class, ClockConfig.class, JwtTokenService.class,
		EmployeeJwtAuthenticationConverter.class, RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class,
		SecurityErrorResponseWriter.class })
public class WebSecurityTestConfig {

	@Bean
	EmployeeRepository employeeRepository() {
		return new EmployeeRepository() {

			@Override
			public void add(Employee employee) {
				throw new UnsupportedOperationException();
			}

			@Override
			public Optional<Employee> findById(UUID id) {
				return Optional.empty();
			}

			@Override
			public Optional<Employee> findByLoginId(String loginId) {
				return Optional.empty();
			}

			@Override
			public Optional<EmployeeAuthStatus> findAuthStatus(UUID employeeId, UUID jti) {
				return Optional.empty();
			}

		};
	}

}

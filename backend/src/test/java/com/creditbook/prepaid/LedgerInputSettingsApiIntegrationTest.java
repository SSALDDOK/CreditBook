package com.creditbook.prepaid;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.creditbook.TestcontainersConfiguration;
import com.creditbook.auth.domain.Employee;
import com.creditbook.auth.domain.EmployeeRepository;
import com.creditbook.auth.domain.Role;
import com.creditbook.global.security.JwtTokenService;
import com.creditbook.global.security.SessionCookies;
import com.creditbook.support.RequiresDocker;

import jakarta.servlet.http.Cookie;

@AutoConfigureMockMvc
@RequiresDocker
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
@Tag("REQ-6")
@Tag("REQ-31")
class LedgerInputSettingsApiIntegrationTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	EmployeeRepository employeeRepository;

	@Autowired
	PasswordEncoder passwordEncoder;

	@Autowired
	JwtTokenService jwtTokenService;

	@Autowired
	Clock clock;

	@ParameterizedTest
	@EnumSource(Role.class)
	@DisplayName("로그인한 직원은 역할과 관계없이 충전 한도와 메모 문구를 설정값 그대로 받는다")
	void settings_returns_configured_values_for_any_role(Role role) throws Exception {
		// given STAFF 또는 ADMIN으로 로그인
		Cookie session = loginAs("settings-" + role.name().toLowerCase(), role);

		// when 화면 입력 설정 조회
		// then application.yml 값 그대로, 문구는 설정에 적은 순서대로
		mockMvc.perform(get("/api/settings/ledger-input").cookie(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.chargeMaxAmount").value(300000))
				.andExpect(jsonPath("$.chargeMemoPresets[0]").value("현금"))
				.andExpect(jsonPath("$.chargeMemoPresets[1]").value("카드"))
				.andExpect(jsonPath("$.chargeMemoPresets[2]").value("계좌이체"))
				.andExpect(jsonPath("$.chargeMemoPresets.length()").value(3))
				.andExpect(jsonPath("$.useMemoPresets[0]").value("음료"))
				.andExpect(jsonPath("$.useMemoPresets[5]").value("기타"))
				.andExpect(jsonPath("$.useMemoPresets.length()").value(6))
				.andExpect(jsonPath("$.memoMaxLength").value(200))
				// 한도가 300000.0 이나 3E+5 로 나가면 화면 비교가 어긋나므로 원문으로 한 번 더 본다
				.andExpect(content().string(containsString("\"chargeMaxAmount\":300000,")));
	}

	@Test
	@DisplayName("로그인하지 않으면 화면 입력 설정을 받을 수 없다")
	void settings_requires_login() throws Exception {
		// given 세션 쿠키 없음
		// when 화면 입력 설정 조회
		// then 401 UNAUTHENTICATED
		mockMvc.perform(get("/api/settings/ledger-input"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	// 역할만 다른 직원을 새로 만들어 세션 쿠키를 받는다
	private Cookie loginAs(String loginId, Role role) {
		Employee employee = Employee.create(loginId, passwordEncoder.encode("test1234!"), "테스트직원", role,
				clock.instant());
		employeeRepository.add(employee);
		return new Cookie(SessionCookies.NAME, jwtTokenService.issue(employee.getId()).value());
	}

}

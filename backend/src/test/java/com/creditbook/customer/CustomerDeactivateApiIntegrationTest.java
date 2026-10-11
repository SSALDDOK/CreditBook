package com.creditbook.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.creditbook.TestcontainersConfiguration;
import com.creditbook.customer.application.CustomerService;
import com.creditbook.customer.application.RegisteredCustomer;
import com.creditbook.support.RequiresDocker;
import com.creditbook.support.WithMockEmployee;

/**
 * 고객 비활성화 API 권한 통합 테스트 (REQ-16·REQ-4). 화면에서 버튼을 숨기는 것은 보안이 아니므로 API를 직접 호출해 막히는지 본다.
 * 인증은 @WithMockEmployee 로 역할만 바꿔 주입한다 (토큰 검증 자체는 인증 통합 테스트가 맡는다).
 */
@AutoConfigureMockMvc
@RequiresDocker
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class CustomerDeactivateApiIntegrationTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	CustomerService customerService;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	EntityManager entityManager;

	@Test
	@WithMockEmployee(role = "STAFF")
	@DisplayName("STAFF 계정의 고객 비활성화 호출 — 403, 고객 상태 변화 없음")
	@Tag("TC-8")
	@Tag("REQ-16")
	@Tag("REQ-4")
	void staff_cannot_deactivate_customer() throws Exception {
		// given — 잔액 0원인 활성 고객 (ADMIN이면 비활성화되는 조건)
		RegisteredCustomer customer = customerService.register("권한TC8", "01080800008");

		// when & then — 화면 버튼 없이 API를 직접 호출해도 403 (버튼 숨기기는 보안이 아님)
		mockMvc.perform(patch("/api/customers/{id}/deactivate", customer.customerId()))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("FORBIDDEN"));

		assertThat(isActive(customer.customerId())).isTrue(); // 거절됐으니 고객은 그대로 활성
	}

	@Test
	@WithMockEmployee(role = "ADMIN")
	@DisplayName("ADMIN 계정은 같은 조건에서 비활성화된다 — 위 403이 권한 때문임을 보여 주는 대조군")
	@Tag("TC-8")
	@Tag("REQ-4")
	void admin_can_deactivate_customer_with_zero_balance() throws Exception {
		// given — 위와 같은 조건(잔액 0원 활성 고객), 역할만 ADMIN
		RegisteredCustomer customer = customerService.register("권한TC8대조", "01080800009");

		// when & then — 204(성공, 본문 없음)
		mockMvc.perform(patch("/api/customers/{id}/deactivate", customer.customerId()))
				.andExpect(status().isNoContent());

		assertThat(isActive(customer.customerId())).isFalse(); // 실제로 비활성으로 바뀜
	}

	private boolean isActive(UUID customerId) {
		// JPA가 아직 DB에 보내지 않은 변경을 먼저 내보내야 JdbcTemplate 조회에 보인다 (롤백은 그대로)
		entityManager.flush();
		return jdbcTemplate.queryForObject("SELECT active FROM customers WHERE id = ?", Boolean.class, customerId);
	}

}

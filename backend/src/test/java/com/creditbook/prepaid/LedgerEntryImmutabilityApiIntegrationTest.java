package com.creditbook.prepaid;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.creditbook.TestcontainersConfiguration;
import com.creditbook.auth.domain.Employee;
import com.creditbook.auth.domain.EmployeeRepository;
import com.creditbook.auth.domain.Role;
import com.creditbook.customer.application.CustomerService;
import com.creditbook.customer.application.RegisteredCustomer;
import com.creditbook.prepaid.application.ChargeResult;
import com.creditbook.prepaid.application.ChargeService;
import com.creditbook.prepaid.domain.IdempotencyKey;
import com.creditbook.support.RequiresDocker;
import com.creditbook.support.WithMockEmployee;

/**
 * 거래 수정·삭제 API 부재 검증 (REQ-14). DB 트리거 차단(append-only)은 별도 테스트가 맡고,
 * 여기서는 API 계층에 수정·삭제 경로가 없어 405 또는 404로 거부되고 거래 행이 그대로 남는지 본다.
 * 경로 판정까지 가도록 로그인한 직원으로 호출한다 (비로그인이면 401에서 멈춘다).
 */
@AutoConfigureMockMvc
@RequiresDocker
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@WithMockEmployee
@Transactional
@Tag("REQ-14")
class LedgerEntryImmutabilityApiIntegrationTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	EmployeeRepository employeeRepository;

	@Autowired
	PasswordEncoder passwordEncoder;

	@Autowired
	CustomerService customerService;

	@Autowired
	ChargeService chargeService;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	EntityManager entityManager;

	@Autowired
	Clock clock;

	@ParameterizedTest(name = "{0} {1}")
	@CsvSource({
			"PUT,    /api/transactions/{id}",
			"DELETE, /api/transactions/{id}",
			"PUT,    /api/ledger-entries/{id}",
			"PATCH,  /api/ledger-entries/{id}",
			"DELETE, /api/ledger-entries/{id}"
	})
	@DisplayName("거래 수정·삭제 시도는 405 또는 404로 거부되고 거래 레코드는 그대로 남는다")
	@Tag("TC-10")
	void modifying_or_deleting_a_ledger_entry_is_rejected(String method, String path) throws Exception {
		// given — 충전 거래 1건 (/api/ledger-entries/{id}는 충전 응답 Location 헤더가 가리키는 경로)
		Employee employee = Employee.create("tc10-staff", passwordEncoder.encode("test1234!"), "테스트직원", Role.STAFF,
				clock.instant());
		employeeRepository.add(employee);
		RegisteredCustomer customer = customerService.register("불변성TC10", "01010100010");
		ChargeResult charge = chargeService.charge(customer.customerId(), new BigDecimal("10000"), null,
				employee.getId(), IdempotencyKey.of("tc10-charge"));
		entityManager.flush(); //JPA메모리 -> DB로 보내기
		Map<String, Object> before = ledgerRow(charge.entryId()); // (원본) 거래 수정·삭제 전 행 사진 찍어 두기

		// when — 표의 메서드·경로로 "금액을 1원으로 고쳐 줘" 요청
		int status = mockMvc.perform(request(method, path, charge.entryId())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"amount\": 1}"))
				.andReturn().getResponse().getStatus(); // 상태 코드 숫자만 꺼냄

		// then — 404(경로 없음) 또는 405(경로는 있지만 그 메서드 없음) + 거래 행 그대로
		assertThat(status).isIn(404, 405);
		assertThat(ledgerRow(charge.entryId())).isEqualTo(before); // 거래 수정·삭제 후 사진 = 거래 수정·삭제 전 사진
	}

	// 표에서 온 글자 "PUT"·"PATCH"·"DELETE" → 실제 MockMvc 요청(put()·patch()·delete())으로 바꾸기
	private static MockHttpServletRequestBuilder request(String method, String path, UUID id) {
		return switch (method) {
			case "PUT" -> put(path, id);
			case "PATCH" -> patch(path, id);
			case "DELETE" -> delete(path, id);
			default -> throw new IllegalArgumentException(method);
		};
	}

	// 거래 행 "사진" — 칸 이름→값 묶음(Map)으로 읽어 전후 비교에 씀
	private Map<String, Object> ledgerRow(UUID entryId) {
		return jdbcTemplate.queryForMap("SELECT type, amount, balance_after, performed_by FROM ledger_entries WHERE id = ?",
				entryId);
	}

}

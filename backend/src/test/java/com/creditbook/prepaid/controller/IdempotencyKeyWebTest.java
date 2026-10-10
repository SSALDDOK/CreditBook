package com.creditbook.prepaid.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.creditbook.global.security.CurrentEmployee;
import com.creditbook.global.security.WebSecurityTestConfig;
import com.creditbook.prepaid.application.ChargeResult;
import com.creditbook.prepaid.application.ChargeService;
import com.creditbook.prepaid.application.UsageResult;
import com.creditbook.prepaid.application.UsageService;
import com.creditbook.prepaid.domain.IdempotencyKey;
import com.creditbook.prepaid.domain.IdempotencyKeyConflictException;
import com.creditbook.prepaid.domain.IdempotencyKeyReusedException;
import com.creditbook.prepaid.domain.LedgerEntryType;
import com.creditbook.support.WithMockEmployee;

/**
 * 충전·사용 API 의 요청 키(Idempotency-Key) 응답 규칙 (REQ-10). 서비스는 목으로 대신하고 웹 계층만 본다.
 * 두 경로가 같은 규칙을 따르므로 각 테스트를 두 경로에 모두 돌린다.
 */
@WebMvcTest({ ChargeController.class, UsageController.class })
@Import(WebSecurityTestConfig.class)
@WithMockEmployee
@ExtendWith(OutputCaptureExtension.class)
@Tag("REQ-10")
class IdempotencyKeyWebTest {

	private static final UUID CUSTOMER_ID = UUID.fromString("0f9a3c1e-5b7d-4e2a-9c8b-1d2e3f4a5b6c");
	private static final UUID ACCOUNT_ID = UUID.fromString("7a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d");
	private static final UUID ENTRY_ID = UUID.fromString("3c4d5e6f-7a8b-4c9d-8e0f-1a2b3c4d5e6f");
	private static final UUID EMPLOYEE_ID = UUID.fromString("00000000-0000-0000-0000-00000000e001");
	private static final Instant NOW = Instant.parse("2026-10-11T01:00:00Z");
	private static final String KEY_VALUE = "3f2b8c1d-9e4a-4b7c-8d6e-0f1a2b3c4d5e";
	private static final String BODY = "{\"amount\": 4500, \"memo\": \"음료\"}";

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	ChargeService chargeService;

	@MockitoBean
	UsageService usageService;

	@MockitoBean
	CurrentEmployee currentEmployee;

	@BeforeEach
	void setUp() {
		given(currentEmployee.id()).willReturn(EMPLOYEE_ID);
	}

	private static MockHttpServletRequestBuilder request(String path) {
		return post("/api/customers/" + CUSTOMER_ID + "/" + path)
				.contentType(MediaType.APPLICATION_JSON)
				.content(BODY);
	}

	/** 서비스가 거래(잔액 45,500원 후)를 돌려주도록 한다. replayed 면 재응답이다. */
	private void givenServiceReturns(String path, String keyValue, boolean replayed) {
		IdempotencyKey key = IdempotencyKey.of(keyValue);
		if (path.equals("charges")) {
			given(chargeService.charge(CUSTOMER_ID, new BigDecimal("4500"), "음료", EMPLOYEE_ID, key))
					.willReturn(new ChargeResult(ENTRY_ID, ACCOUNT_ID, CUSTOMER_ID, LedgerEntryType.CHARGE,
							new BigDecimal("4500"), new BigDecimal("45500"), "음료", EMPLOYEE_ID, NOW, replayed));
		}
		else {
			given(usageService.use(CUSTOMER_ID, new BigDecimal("4500"), "음료", EMPLOYEE_ID, key))
					.willReturn(new UsageResult(ENTRY_ID, ACCOUNT_ID, CUSTOMER_ID, LedgerEntryType.USE,
							new BigDecimal("4500"), new BigDecimal("45500"), "음료", EMPLOYEE_ID, NOW, replayed));
		}
	}

	private void givenServiceThrows(String path, RuntimeException ex) {
		if (path.equals("charges")) {
			given(chargeService.charge(any(), any(), any(), any(), eq(IdempotencyKey.of(KEY_VALUE)))).willThrow(ex);
		}
		else {
			given(usageService.use(any(), any(), any(), any(), eq(IdempotencyKey.of(KEY_VALUE)))).willThrow(ex);
		}
	}

	private void verifyServiceNeverCalled() {
		verify(chargeService, never()).charge(any(), any(), any(), any(), any());
		verify(usageService, never()).use(any(), any(), any(), any(), any());
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@ValueSource(strings = { "charges", "uses" })
	@DisplayName("처음 온 요청 키면 201로 거래를 돌려주고 재응답 헤더는 싣지 않는다")
	void first_request_returns_201_without_replayed_header(String path) throws Exception {
		// given
		givenServiceReturns(path, KEY_VALUE, false);

		// when / then
		mockMvc.perform(request(path).header(IdempotencyHeaders.IDEMPOTENCY_KEY, KEY_VALUE))
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", endsWith("/api/ledger-entries/" + ENTRY_ID)))
				.andExpect(header().doesNotExist(IdempotencyHeaders.IDEMPOTENT_REPLAYED))
				.andExpect(jsonPath("$.id").value(ENTRY_ID.toString()))
				.andExpect(jsonPath("$.balanceAfter").value(45500));
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@ValueSource(strings = { "charges", "uses" })
	@DisplayName("같은 요청 키로 같은 요청을 다시 보내면 201로 처음과 같은 거래를 돌려주고 Idempotent-Replayed: true 를 싣는다")
	void replayed_request_returns_201_with_same_entry_and_replayed_header(String path) throws Exception {
		// given
		givenServiceReturns(path, KEY_VALUE, true);

		// when / then
		mockMvc.perform(request(path).header(IdempotencyHeaders.IDEMPOTENCY_KEY, KEY_VALUE))
				.andExpect(status().isCreated())
				.andExpect(header().string(IdempotencyHeaders.IDEMPOTENT_REPLAYED, "true"))
				.andExpect(header().string("Location", endsWith("/api/ledger-entries/" + ENTRY_ID)))
				.andExpect(jsonPath("$.id").value(ENTRY_ID.toString()))
				.andExpect(jsonPath("$.customerId").value(CUSTOMER_ID.toString()))
				.andExpect(jsonPath("$.amount").value(4500))
				.andExpect(jsonPath("$.balanceAfter").value(45500))
				.andExpect(jsonPath("$.memo").value("음료"))
				.andExpect(jsonPath("$.performedAt").value("2026-10-11T01:00:00Z"));
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@ValueSource(strings = { "charges", "uses" })
	@DisplayName("같은 요청 키로 다른 요청을 보내면 422 IDEMPOTENCY_KEY_REUSED 로 거절한다")
	void reused_key_for_different_request_returns_422(String path) throws Exception {
		// given
		givenServiceThrows(path, new IdempotencyKeyReusedException(ENTRY_ID));

		// when / then
		mockMvc.perform(request(path).header(IdempotencyHeaders.IDEMPOTENCY_KEY, KEY_VALUE))
				.andExpect(status().isUnprocessableContent())
				.andExpect(header().doesNotExist(IdempotencyHeaders.IDEMPOTENT_REPLAYED))
				.andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"))
				.andExpect(jsonPath("$.message").value("같은 요청 키로 다른 거래를 요청했습니다. 새로 시도해 주세요."))
				.andExpect(jsonPath("$.fieldErrors").isEmpty())
				.andExpect(jsonPath("$.details").doesNotExist());
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@ValueSource(strings = { "charges", "uses" })
	@DisplayName("같은 키 동시 요청에서 먼저 저장된 거래를 찾지 못하면 409 CONCURRENT_MODIFICATION 으로 응답한다")
	void unresolved_concurrent_same_key_returns_409(String path) throws Exception {
		// given
		givenServiceThrows(path, new IdempotencyKeyConflictException(new RuntimeException("duplicate key")));

		// when / then
		mockMvc.perform(request(path).header(IdempotencyHeaders.IDEMPOTENCY_KEY, KEY_VALUE))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@ValueSource(strings = { "charges", "uses" })
	@DisplayName("요청 키 헤더가 없으면 400 INVALID_IDEMPOTENCY_KEY 로 거절하고 거래를 만들지 않는다")
	void missing_key_returns_400(String path, CapturedOutput output) throws Exception {
		// when / then
		mockMvc.perform(request(path))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"))
				.andExpect(jsonPath("$.message").value("요청 키(Idempotency-Key)를 확인해 주세요."))
				.andExpect(jsonPath("$.fieldErrors").isEmpty());
		verifyServiceNeverCalled();
		assertThat(output.getOut().lines().filter(line -> line.contains("invalid idempotency key")))
				.singleElement()
				.asString()
				.contains("WARN");
	}

	@ParameterizedTest(name = "[{index}] {0} \"{1}\"")
	@CsvSource(delimiter = '|', value = {
			"charges | aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaZ",
			"uses    | aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaZ",
			"charges | bad_key_value",
			"uses    | bad_key_value",
			"charges | bad.key.value",
			"uses    | '<script>leak</script>'" })
	@DisplayName("요청 키가 65자 이상이거나 형식이 틀리면 400으로 거절하고 입력값을 응답·로그에 되돌리지 않는다")
	void malformed_key_returns_400_without_echoing_value(String path, String keyValue, CapturedOutput output)
			throws Exception {
		// when
		MvcResult result = mockMvc.perform(request(path).header(IdempotencyHeaders.IDEMPOTENCY_KEY, keyValue))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"))
				.andReturn();

		// then
		verifyServiceNeverCalled();
		assertThat(result.getResponse().getContentAsString()).doesNotContain(keyValue);
		assertThat(output.getOut()).doesNotContain(keyValue);
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@ValueSource(strings = { "charges", "uses" })
	@DisplayName("요청 키는 64자까지 받는다")
	void key_of_max_length_is_accepted(String path) throws Exception {
		// given
		String key64 = "a".repeat(IdempotencyKey.MAX_LENGTH);
		givenServiceReturns(path, key64, false);

		// when / then
		mockMvc.perform(request(path).header(IdempotencyHeaders.IDEMPOTENCY_KEY, key64))
				.andExpect(status().isCreated());
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@ValueSource(strings = { "charges", "uses" })
	@DisplayName("요청 키보다 로그인 확인이 먼저다 — 현재 직원을 알 수 없으면 키가 없어도 401로 거절한다")
	void unauthenticated_is_checked_before_key(String path) throws Exception {
		// given
		given(currentEmployee.id()).willReturn(null);

		// when / then
		mockMvc.perform(request(path))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
		verifyServiceNeverCalled();
	}

}

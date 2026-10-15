package com.creditbook.prepaid;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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
import com.creditbook.global.security.JwtTokenService;
import com.creditbook.global.security.SessionCookies;
import com.creditbook.support.RequiresDocker;

import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@AutoConfigureMockMvc
@RequiresDocker
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional // 테스트마다 롤백 → 매번 새 고객·새 키
@Tag("REQ-7")
class ChargeCancelApiIntegrationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    MockMvc mockMvc;

    @Autowired
    EmployeeRepository employeeRepository;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    JwtTokenService jwtTokenService;

    @Autowired
    CustomerService customerService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    EntityManager entityManager;

    @Autowired
    Clock clock;

    @Test
    @DisplayName("오늘 등록된 충전 건에 대해 사유를 입력해 취소하면 원 거래는 남고 반제(CHARGE_CANCEL) 거래가 추가되어 잔액이 충전 금액만큼 줄어든다")
    @Tag("TC-43")
    void cancel_todays_charge_adds_charge_cancel()
            throws Exception {
        // given — 직원 로그인, 고객 A에 30,000원 충전 (충전 거래 id 꺼내 두기)
        String loginId = "tc43-staff";
        Cookie session = loginAsNewStaff(loginId);
        RegisteredCustomer customer = customerService.register("TC43", "01043431001");
        String chargeBody = mockMvc
                .perform(withSession(post("/api/customers/{id}/charges", customer.customerId()), session)
                        .header("Idempotency-Key", "tc43-charge")
                        .content("{\"amount\": 30000}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String chargeId = idOf(chargeBody);

        // when — POST /api/customers/{customerId}/charges/{chargeEntryId}/cancel
        mockMvc.perform(
                withSession(
                        post("/api/customers/{customerId}/charges/{chargeEntryId}/cancel", customer.customerId(),
                                chargeId),
                        session)
                        .header("Idempotency-Key", "tc43-charge-cancel")
                        .content(reasonBody("충전 취소")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reversesId").value(chargeId))
                .andExpect(jsonPath("$.amount").value(30000))
                .andExpect(jsonPath("$.type").value("CHARGE_CANCEL"))
                .andExpect(jsonPath("$.balanceAfter").value(0));
        // then 201, 응답 type CHARGE_CANCEL, reversesId = 충전 id, amount 30000,
        // balanceAfter 0
        // DB 잔액 0, CHARGE_CANCEL 1건, CHARGE 1건 그대로
        // CHARGE_CANCEL의 performed_by가 로그인한 STAFF인지
        entityManager.flush();
        assertThat(performedBy(customer.accountId())).isEqualTo(currentStaffId(loginId));
        assertThat(countEntries(customer.accountId(), "CHARGE")).isEqualTo(1);
        assertThat(countEntries(customer.accountId(), "CHARGE_CANCEL")).isEqualTo(1);
        assertThat(balanceOf(customer.accountId())).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("잔액이 충전액과 같으면 전액 취소된다 — 201, 잔액 0원")
    @Tag("TC-43")
    void cancel_succeeds_when_balance_equals_charge_amount() throws Exception {
        // given — 직원 로그인, 고객 B 10,000원 충전 → 응답에서 id를 꺼내기 (이 건을 취소할 것) 20,000원 충전 →
        // 20,000원 사용 post("/api/customers/{id}/uses", ...), 본문 {"amount": 20000}
        String loginId = "tc43-staffB";
        Cookie session = loginAsNewStaff(loginId);
        RegisteredCustomer customer = customerService.register("TC43", "01043432002");
        // 10000원 충전
        String chargeBody = mockMvc
                .perform(withSession(post("/api/customers/{id}/charges", customer.customerId()), session)
                        .header("Idempotency-Key", "tc43-eq-charge-1")
                        .content("{\"amount\": 10000}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String firstChargeId = idOf(chargeBody);
        // 20000원 충전
        mockMvc.perform(withSession(post("/api/customers/{id}/charges", customer.customerId()), session)
                .header("Idempotency-Key", "tc43-eq-charge-2")
                .content("{\"amount\": 20000}"))
                .andExpect(status().isCreated());

        // 20000원 사용
        mockMvc.perform(withSession(post("/api/customers/{id}/uses", customer.customerId()), session)
                .header("Idempotency-Key", "tc43-eq-use")
                .content("{\"amount\": 20000}"))
                .andExpect(status().isCreated());

        // when — 1번의 10,000원 충전 건 취소
        mockMvc.perform(
                withSession(
                        post("/api/customers/{customerId}/charges/{chargeEntryId}/cancel", customer.customerId(),
                                firstChargeId),
                        session)
                        .header("Idempotency-Key", "tc43-eq-cancel")
                        .content(reasonBody("금액 잘못 입력")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reversesId").value(firstChargeId))
                .andExpect(jsonPath("$.balanceAfter").value(0))
                .andExpect(jsonPath("$.type").value("CHARGE_CANCEL"));

        // then — 응답 201, balanceAfter 0 ,CHARGE_CANCEL 1건 DB 잔액 0원
        entityManager.flush();
        assertThat(performedBy(customer.accountId())).isEqualTo(currentStaffId(loginId));
        assertThat(balanceOf(customer.accountId())).isEqualByComparingTo("0");
        assertThat(countEntries(customer.accountId(), "CHARGE_CANCEL")).isEqualTo(1);
    }

    @Test
    @DisplayName("취소 후 잔액이 음수가 되면 거절되고 '사용 건을 먼저 취소해 주세요' 안내가 뜬다 — 잔액이 충전액보다 1원 적으면 409 CHARGE_ALREADY_USED, 장부 변화 없음")
    @Tag("TC-43")
    void cancel_fails_when_balance_is_one_won_short() throws Exception {
        // given — 직원 로그인, 고객 C 20,000원 충전(이 건을 취소할 것) → 1원 사용, 잔액 19,999원
        Cookie session = loginAsNewStaff("tc43-staffC");
        RegisteredCustomer customer = customerService.register("TC43", "01043433003");
        // 20000원 충전
        String chargeBody = mockMvc
                .perform(withSession(post("/api/customers/{id}/charges", customer.customerId()), session)
                        .header("Idempotency-Key", "tc43-short-charge")
                        .content("{\"amount\": 20000}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String chargeId = idOf(chargeBody);
        // 1원 사용 — 잔액이 충전액보다 딱 1원 적은 경계를 만든다
        mockMvc.perform(withSession(post("/api/customers/{id}/uses", customer.customerId()), session)
                .header("Idempotency-Key", "tc43-short-use")
                .content("{\"amount\": 1}"))
                .andExpect(status().isCreated());

        // when — 20,000원 충전 건 취소
        mockMvc.perform(
                withSession(
                        post("/api/customers/{customerId}/charges/{chargeEntryId}/cancel", customer.customerId(),
                                chargeId),
                        session)
                        .header("Idempotency-Key", "tc43-short-cancel")
                        .content(reasonBody("금액 잘못 입력")))
                // 거절 응답은 {code, message, fieldErrors, details} 모양 — balanceAfter·type 없음
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHARGE_ALREADY_USED"))
                .andExpect(jsonPath("$.message").value("잔액이 부족해 취소할 수 없어요. 먼저 사용 건을 취소해 주세요."))
                .andExpect(jsonPath("$.details.balance").value(19999))
                .andExpect(jsonPath("$.details.chargeAmount").value(20000));

        // then — 장부 변화 없음: 잔액 19,999원 그대로, 취소 거래 없음, 원 충전 1건 그대로
        entityManager.flush();
        assertThat(balanceOf(customer.accountId())).isEqualByComparingTo("19999");
        assertThat(countEntries(customer.accountId(), "CHARGE_CANCEL")).isZero();
        assertThat(countEntries(customer.accountId(), "CHARGE")).isEqualTo(1);
    }

    @Test
    @DisplayName("이미 사용한 금액이 있는 충전 건은 취소할 수 없다 — 잔액 0원에서 20,000원 충전 건은 409 CHARGE_ALREADY_USED, 장부 변화 없음")
    @Tag("TC-43")
    void cancel_fails_when_balance_is_zero() throws Exception {
        // given — 항목 2와 같은 준비: 고객 B 10,000원 충전 → 20,000원 충전(이 건을 취소할 것) → 20,000원 사용
        // → 10,000원 충전 건 취소까지 해서 잔액 0원
        // (항목 2 테스트에 기대지 않으려고 여기서 다시 준비한다)
        Cookie session = loginAsNewStaff("tc43-staffB2");
        RegisteredCustomer customer = customerService.register("TC43", "01043434004");
        // 10000원 충전
        String firstChargeBody = mockMvc
                .perform(withSession(post("/api/customers/{id}/charges", customer.customerId()), session)
                        .header("Idempotency-Key", "tc43-zero-charge-1")
                        .content("{\"amount\": 10000}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String firstChargeId = idOf(firstChargeBody);
        // 20000원 충전
        String secondChargeBody = mockMvc
                .perform(withSession(post("/api/customers/{id}/charges", customer.customerId()), session)
                        .header("Idempotency-Key", "tc43-zero-charge-2")
                        .content("{\"amount\": 20000}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String secondChargeId = idOf(secondChargeBody);
        // 20000원 사용
        mockMvc.perform(withSession(post("/api/customers/{id}/uses", customer.customerId()), session)
                .header("Idempotency-Key", "tc43-zero-use")
                .content("{\"amount\": 20000}"))
                .andExpect(status().isCreated());
        // 10000원 충전 건 취소 → 잔액 0원
        mockMvc.perform(
                withSession(
                        post("/api/customers/{customerId}/charges/{chargeEntryId}/cancel", customer.customerId(),
                                firstChargeId),
                        session)
                        .header("Idempotency-Key", "tc43-zero-cancel-1")
                        .content(reasonBody("금액 잘못 입력")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.balanceAfter").value(0));

        // when — 남은 20,000원 충전 건 취소 (잔액 0원 < 20,000원)
        mockMvc.perform(
                withSession(
                        post("/api/customers/{customerId}/charges/{chargeEntryId}/cancel", customer.customerId(),
                                secondChargeId),
                        session)
                        .header("Idempotency-Key", "tc43-zero-cancel-2")
                        .content(reasonBody("금액 잘못 입력")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHARGE_ALREADY_USED"))
                .andExpect(jsonPath("$.details.balance").value(0))
                .andExpect(jsonPath("$.details.chargeAmount").value(20000));

        // then — 장부 변화 없음: 잔액 0원 그대로, 취소 거래는 앞에서 성공한 1건뿐
        entityManager.flush();
        assertThat(balanceOf(customer.accountId())).isEqualByComparingTo("0");
        assertThat(countEntries(customer.accountId(), "CHARGE_CANCEL")).isEqualTo(1);
    }

    // 직원 저장 → 진짜 토큰 발급 → 로그인 쿠키(CB_SESSION)에 담기 (거래에 처리 직원이 실제로 있어야 해서)
    private Cookie loginAsNewStaff(String loginId) {
        Employee employee = Employee.create(loginId, passwordEncoder.encode("test1234!"), "테스트직원", Role.STAFF,
                clock.instant());
        employeeRepository.add(employee);
        return new Cookie(SessionCookies.NAME, jwtTokenService.issue(employee.getId()).value());
    }

    private static MockHttpServletRequestBuilder withSession(MockHttpServletRequestBuilder request, Cookie session) {
        return request.cookie(session).contentType(MediaType.APPLICATION_JSON);
    }

    // 취소 요청 본문 — JSON은 손으로 이어 붙이지 않고 JsonMapper로
    private static String reasonBody(String reason) {
        return JSON.writeValueAsString(Map.of("reason", reason));
    }

    // 응답 JSON에서 "id" 값만 꺼내기
    private static String idOf(String responseBody) {
        JsonNode body = JSON.readTree(responseBody);
        return body.get("id").asString();
    }

    private BigDecimal balanceOf(UUID accountId) {
        return jdbcTemplate.queryForObject("SELECT balance FROM prepaid_accounts WHERE id = ?", BigDecimal.class,
                accountId);
    }

    private int countEntries(UUID accountId, String type) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM ledger_entries WHERE account_id = ? AND type = ?",
                Integer.class, accountId, type);
    }

    private UUID currentStaffId(String loginId) {
        return jdbcTemplate.queryForObject("SELECT id FROM employees WHERE login_id = ?",
                UUID.class, loginId);
    }

    // 충전 취소한 STAFF확인
    private UUID performedBy(UUID accountId) {
        return jdbcTemplate.queryForObject(
                "SELECT performed_by FROM ledger_entries WHERE account_id = ? AND type = 'CHARGE_CANCEL'",
                UUID.class, accountId);
    }

}

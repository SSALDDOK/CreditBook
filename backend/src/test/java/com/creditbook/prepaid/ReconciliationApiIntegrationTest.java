package com.creditbook.prepaid;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;

import org.assertj.core.api.SoftAssertions;
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

import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@AutoConfigureMockMvc
@RequiresDocker
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional // 테스트마다 롤백 → 매번 새 고객·새 키
@Tag("REQ-28")
class ReconciliationApiIntegrationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    // 정합성 점검 쿼리 — 확정본은 DB 스키마 정의서 §6 (운영 점검 가이드 §2.1·§2.2와 같은 SQL). 문서가 바뀌면 여기도 같이 고친다
    // 모두 "틀린 행"을 찾는 쿼리라 0행이어야 정상

    // §2.1 계좌 잔액과 거래 합계가 다른 계좌
    private static final String SQL_MISMATCHED_ACCOUNTS = """
            SELECT a.id AS account_id, c.name,
                   a.balance                          AS stored,
                   COALESCE(SUM(t.signed_amount), 0)  AS computed,
                   a.balance - COALESCE(SUM(t.signed_amount), 0) AS diff
            FROM prepaid_accounts a
            JOIN customers c ON c.id = a.customer_id
            LEFT JOIN ledger_entries t ON t.account_id = a.id
            GROUP BY a.id, c.name, a.balance
            HAVING a.balance <> COALESCE(SUM(t.signed_amount), 0)
            """;
    // §2.2 음수 잔액 (CHECK 제약이 막지만 이중 확인)
    private static final String SQL_NEGATIVE_BALANCES = """
            SELECT id, balance FROM prepaid_accounts WHERE balance < 0
            """;

    // §2.2 balance_after 누적합 불일치 — seq 순서로 누적한 값과 각 거래의 '거래 후 잔액' 비교
    private static final String SQL_BALANCE_AFTER_MISMATCHES = """
            WITH running AS (
                SELECT id, account_id, seq, performed_at, balance_after,
                       SUM(signed_amount) OVER (
                           PARTITION BY account_id
                           ORDER BY seq
                           ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
                       ) AS expected
                FROM ledger_entries
            )
            SELECT id, account_id, seq, performed_at, balance_after, expected,
                   balance_after - expected AS diff
            FROM running
            WHERE balance_after <> expected
            ORDER BY account_id, seq
            """;

    // §2.2 반제인데 원본 참조가 없는 경우 (CHECK 제약이 막지만 이중 확인)
    private static final String SQL_CANCELS_WITHOUT_ORIGIN = """
            SELECT id, type FROM ledger_entries
            WHERE type IN ('CHARGE_CANCEL', 'USE_CANCEL') AND reverses_id IS NULL
            """;

    // §2.2 반제 금액 ≠ 원본 금액
    private static final String SQL_CANCEL_AMOUNT_MISMATCHES = """
            SELECT r.id AS cancel_id, r.amount AS cancel_amount, o.amount AS origin_amount
            FROM ledger_entries r
            JOIN ledger_entries o ON o.id = r.reverses_id
            WHERE r.amount <> o.amount
            """;

    // §2.2 반제 대상 유형·계좌 불일치 (충전 취소가 충전이 아닌 걸, 사용 취소가 사용이 아닌 걸, 다른 계좌를 가리키는 경우)
    private static final String SQL_CANCEL_TARGET_MISMATCHES = """
            SELECT r.id AS cancel_id, r.type, o.type AS origin_type, r.account_id, o.account_id AS origin_account
            FROM ledger_entries r
            JOIN ledger_entries o ON o.id = r.reverses_id
            WHERE r.account_id <> o.account_id
               OR (r.type = 'CHARGE_CANCEL' AND o.type <> 'CHARGE')
               OR (r.type = 'USE_CANCEL'    AND o.type <> 'USE')
            """;

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
    Clock clock;

    @Test
    @DisplayName("충전 → 사용 → 사용 취소 → 충전 취소 후 정합성 점검 쿼리(§2.1·§2.2)를 돌리면 모두 0행이다")
    void reconciliation_queries_return_no_rows_after_charge_use_and_both_cancels() throws Exception {
        // given — STAFF 로그인, 고객 1명
        String loginId = "rec28-staff";
        Cookie session = loginAsNewStaff(loginId);
        RegisteredCustomer customer = customerService.register("REQ28", "01043435005");

        // when — 충전 2건 → 사용 → 사용 취소 → 충전 취소 (API로, 멱등키는 호출마다 다르게)
        // ① 충전 10,000 → 잔액 10,000
        mockMvc
                .perform(withSession(post("/api/customers/{id}/charges", customer.customerId()), session)
                        .header("Idempotency-Key", "rec28-charge-first")
                        .content("{\"amount\": 10000}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.balanceAfter").value(10000));

        // ② 충전 5,000 (id 보관 — ⑤에서 취소) → 잔액 15,000
        String secondChargeBody = mockMvc
                .perform(withSession(post("/api/customers/{id}/charges", customer.customerId()), session)
                        .header("Idempotency-Key", "rec28-charge-second")
                        .content("{\"amount\": 5000}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.balanceAfter").value(15000))
                .andReturn().getResponse().getContentAsString();

        String chargeId = idOf(secondChargeBody);

        // ③ 사용 3,000 (id 보관) → 잔액 12,000
        String useBody = mockMvc.perform(withSession(post("/api/customers/{id}/uses", customer.customerId()), session)
                .header("Idempotency-Key", "rec28-use")
                .content("{\"amount\": 3000}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value(3000))
                .andExpect(jsonPath("$.balanceAfter").value(12000))
                .andReturn().getResponse().getContentAsString();

        String useId = idOf(useBody);

        // ④ ③ 사용 취소 (사유) → 잔액 15,000
        mockMvc.perform(withSession(post("/api/customers/{customerId}/uses/{useEntryId}/cancel",
                customer.customerId(), useId), session)
                .header("Idempotency-Key", "rec28-use-cancel")
                .content(reasonBody("주문 착오")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("USE_CANCEL"))
                .andExpect(jsonPath("$.reversesId").value(useId))
                .andExpect(jsonPath("$.amount").value(3000))
                .andExpect(jsonPath("$.balanceAfter").value(15000));

        // ⑤ ② 충전 취소 (사유) → 잔액 10,000 ← ④ 다음이어야 음수 거절이 안 남
        mockMvc.perform(
                withSession(
                        post("/api/customers/{customerId}/charges/{chargeEntryId}/cancel", customer.customerId(),
                                chargeId),
                        session)
                        .header("Idempotency-Key", "rec28-charge-cancel")
                        .content(reasonBody("충전 취소")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reversesId").value(chargeId))
                .andExpect(jsonPath("$.amount").value(5000))
                .andExpect(jsonPath("$.balanceAfter").value(10000))
                .andExpect(jsonPath("$.type").value("CHARGE_CANCEL"))
                .andExpect(jsonPath("$.reason").value("충전 취소"));

        // then 1 — 이 시나리오가 의도대로 됐는지
        // 잔액 10,000원 (isEqualByComparingTo)
        assertThat(balanceOf(customer.accountId())).isEqualByComparingTo("10000");
        // 거래 5건 (CHARGE 2, USE 1, USE_CANCEL 1, CHARGE_CANCEL 1)
        assertThat(countEntries(customer.accountId(), "CHARGE")).isEqualTo(2);
        assertThat(countEntries(customer.accountId(), "CHARGE_CANCEL")).isEqualTo(1);
        assertThat(countEntries(customer.accountId(), "USE")).isEqualTo(1);
        assertThat(countEntries(customer.accountId(), "USE_CANCEL")).isEqualTo(1);

        // then 2 — 정합성 점검 쿼리 6개가 모두 0행 (softly: 하나가 실패해도 6개를 다 돌리고 한꺼번에 보여 준다)
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(jdbcTemplate.queryForList(SQL_MISMATCHED_ACCOUNTS)).as("§2.1 불일치 계좌").isEmpty();
            softly.assertThat(jdbcTemplate.queryForList(SQL_NEGATIVE_BALANCES)).as("§2.2 음수 잔액").isEmpty();
            softly.assertThat(jdbcTemplate.queryForList(SQL_BALANCE_AFTER_MISMATCHES))
                    .as("§2.2 balance_after 누적합 불일치").isEmpty();
            softly.assertThat(jdbcTemplate.queryForList(SQL_CANCELS_WITHOUT_ORIGIN))
                    .as("§2.2 원본 참조 없는 반제").isEmpty();
            softly.assertThat(jdbcTemplate.queryForList(SQL_CANCEL_AMOUNT_MISMATCHES))
                    .as("§2.2 반제 금액 ≠ 원본 금액").isEmpty();
            softly.assertThat(jdbcTemplate.queryForList(SQL_CANCEL_TARGET_MISMATCHES))
                    .as("§2.2 반제 대상 유형·계좌 불일치").isEmpty();
        });

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
}

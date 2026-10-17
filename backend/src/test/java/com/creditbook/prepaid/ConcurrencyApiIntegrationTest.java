package com.creditbook.prepaid;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

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
// @Transactional 붙이지 않는다 — 두 스레드가 각자 실제로 커밋해야 동시성이 재현됨
public class ConcurrencyApiIntegrationTest {
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
    TransactionTemplate transactionTemplate;
    @Autowired
    Clock clock;

    @Test
    @DisplayName("같은 계좌에 사용 요청 2건이 동시에 오면 1건만 반영되고 잔액은 음수가 되지 않는다")
    @Tag("REQ-10")
    @Tag("TC-40")
    void concurrent_uses_on_same_account_apply_only_once() throws Exception {
        // given — 직원 로그인, 고유한 고객 등록, 5,000원 충전 (모두 커밋됨)
        Cookie session = loginAsNewStaff();
        String suffix = UUID.randomUUID().toString().substring(0, 4); // 매번 다른 4글자
        RegisteredCustomer customer = customerService.register("TC40" + suffix,
                "010" + String.format("%04d", new Random().nextInt(10000)) + "4040");
        // 충전 API 호출 — 키는 UUID
        mockMvc.perform(withSession(post("/api/customers/{id}/charges", customer.customerId()), session)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .content("{\"amount\": 5000}"))
                .andExpect(status().isCreated());

        assertThat(balanceOf(customer.accountId())).isEqualByComparingTo("5000");
        // when — 서로 다른 키로 4,500원 사용 2건을 동시에
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch ready = new CountDownLatch(2); // 두 일꾼이 출발선에 섰는지

        try {
            List<Future<MvcResult>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown(); // 출발선에 섰다
                    start.await(); // 둘 다 여기서 기다렸다가 동시에 출발
                    return mockMvc.perform(withSession(post("/api/customers/{id}/uses", customer.customerId()), session)
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .content("{\"amount\": 4500}"))
                            .andReturn(); // 상태+본문 통째로 (검사는 메인에서)
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue(); // 둘 다 섰는지 확인하고 쏜다
            start.countDown();

            List<MvcResult> results = new ArrayList<>();
            for (Future<MvcResult> f : futures) {
                results.add(f.get(10, TimeUnit.SECONDS));
            }

            // then — 메인 스레드에서만 단언
            // 201은 정확히 1건, 나머지는 409 또는 400
            List<Integer> statuses = results.stream().map(r -> r.getResponse().getStatus()).toList();
            assertThat(statuses).containsOnlyOnce(201);
            for (MvcResult r : results) {
                if (r.getResponse().getStatus() == 201)
                    continue;
                String code = JSON.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).get("code")
                        .asString();
                // 겹치면 409 낙관적 락, 안 겹치면 400 잔액 부족 — 그 외는 실패
                assertThat(r.getResponse().getStatus() + " " + code)
                        .isIn("409 CONCURRENT_MODIFICATION", "400 INSUFFICIENT_BALANCE");
            }
            // 장부: 잔액 500원, 사용 1건, 잔액 = 거래 합계
            assertThat(balanceOf(customer.accountId())).isEqualByComparingTo("500");
            assertThat(countEntries(customer.accountId(), "USE")).isEqualTo(1);
            BigDecimal ledgerSum = jdbcTemplate.queryForObject(
                    "SELECT sum(signed_amount) FROM ledger_entries WHERE account_id = ?", BigDecimal.class,
                    customer.accountId());
            assertThat(balanceOf(customer.accountId())).isEqualByComparingTo(ledgerSum);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("같은 사용 건에 취소 2건이 동시에 오면 1건만 반영되고 잔액은 한 번만 복구된다")
    @Tag("REQ-34")
    @Tag("TC-47")
    void concurrent_cancels_of_same_use_apply_only_once() throws Exception {
        // given — 직원 로그인, 고유한 고객 등록, 5,000원 충전 (모두 커밋됨)
        Cookie session = loginAsNewStaff();
        String suffix = UUID.randomUUID().toString().substring(0, 4); // 매번 다른 4글자
        RegisteredCustomer customer = customerService.register("REQ34" + suffix,
                "010" + String.format("%04d", new Random().nextInt(10000)) + "5050");
        // 충전 API 호출 — 키는 UUID
        mockMvc.perform(withSession(post("/api/customers/{id}/charges", customer.customerId()), session)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .content("{\"amount\": 5000}"))
                .andExpect(status().isCreated());

        String useBody = mockMvc.perform(withSession(post("/api/customers/{id}/uses", customer.customerId()), session)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .content("{\"amount\": 3000}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String useId = idOf(useBody);
        assertThat(balanceOf(customer.accountId())).isEqualByComparingTo("2000");
        // when — 같은 사용 건에 취소 2건을 동시에
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch ready = new CountDownLatch(2); // 두 일꾼이 출발선에 섰는지

        try {
            List<Future<MvcResult>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown(); // 출발선에 섰다
                    start.await(); // 둘 다 여기서 기다렸다가 동시에 출발
                    return mockMvc
                            .perform(withSession(post("/api/customers/{customerId}/uses/{useEntryId}/cancel",
                                    customer.customerId(), useId), session)
                                    .header("Idempotency-Key", UUID.randomUUID().toString())
                                    .content("{\"reason\": \"동시 취소 테스트\"}"))
                            .andReturn(); // 상태+본문 통째로 (검사는 메인에서)
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue(); // 둘 다 섰는지 확인하고 쏜다
            start.countDown();

            List<MvcResult> results = new ArrayList<>();
            for (Future<MvcResult> f : futures) {
                results.add(f.get(10, TimeUnit.SECONDS));
            }

            // then — 메인 스레드에서만 단언
            // 201은 정확히 1건, 나머지는 409
            List<Integer> statuses = results.stream().map(r -> r.getResponse().getStatus()).toList();
            assertThat(statuses).containsOnlyOnce(201);
            for (MvcResult r : results) {
                if (r.getResponse().getStatus() == 201)
                    continue;
                String code = JSON.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).get("code")
                        .asString();
                // 겹쳐도 다시 판정해서 '이미 취소됨'으로 거절돼야 함 — 그 외(500, 낙관적 락 409 등)는 실패
                assertThat(r.getResponse().getStatus() + " " + code).isEqualTo("409 ALREADY_CANCELLED");
            }
            // 장부: 잔액 5,000원으로 한 번만 복구, 취소 1건, 잔액 = 거래 합계
            assertThat(balanceOf(customer.accountId())).isEqualByComparingTo("5000");
            assertThat(countEntries(customer.accountId(), "USE_CANCEL")).isEqualTo(1);
            BigDecimal ledgerSum = jdbcTemplate.queryForObject(
                    "SELECT sum(signed_amount) FROM ledger_entries WHERE account_id = ?", BigDecimal.class,
                    customer.accountId());
            assertThat(balanceOf(customer.accountId())).isEqualByComparingTo(ledgerSum);
        } finally {
            executor.shutdownNow();
        }
    }

    // 직원은 트랜잭션 안에서 만들어야 커밋된다
    private Cookie loginAsNewStaff() {
        Employee employee = transactionTemplate.execute(status -> {
            Employee e = Employee.create("tc40-" + UUID.randomUUID(), passwordEncoder.encode("test1234!"),
                    "테스트직원", Role.STAFF, clock.instant());
            employeeRepository.add(e);
            return e;
        });
        return new Cookie(SessionCookies.NAME, jwtTokenService.issue(employee.getId()).value());
    }

    private static MockHttpServletRequestBuilder withSession(MockHttpServletRequestBuilder request, Cookie session) {
        return request.cookie(session).contentType(MediaType.APPLICATION_JSON);
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

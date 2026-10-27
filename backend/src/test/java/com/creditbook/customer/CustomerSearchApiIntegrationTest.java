package com.creditbook.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.creditbook.TestcontainersConfiguration;
import com.creditbook.auth.domain.Employee;
import com.creditbook.auth.domain.EmployeeRepository;
import com.creditbook.auth.domain.Role;
import com.creditbook.customer.application.CustomerService;
import com.creditbook.customer.application.RegisteredCustomer;
import com.creditbook.prepaid.application.ChargeService;
import com.creditbook.prepaid.domain.IdempotencyKey;
import com.creditbook.support.RequiresDocker;
import com.creditbook.support.WithMockEmployee;
import com.jayway.jsonpath.JsonPath;

import jakarta.persistence.EntityManager;

@AutoConfigureMockMvc
@RequiresDocker
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@WithMockEmployee
@Transactional
@Tag("REQ-2")
class CustomerSearchApiIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    CustomerService customerService;

    @Autowired
    ChargeService chargeService;

    @Autowired
    EmployeeRepository employeeRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    Clock clock;

    @Autowired
    EntityManager entityManager;

    @Test
    @DisplayName("이름 일부 검색 시 해당 고객만 잔액과 함께 반환")
    @Tag("TC-29")
    void search_by_partial_name_returns_only_matching_customers_with_balance() throws Exception {
        // given 고객 등록
        customerService.register("김고객TC29", "01012345678");
        customerService.register("고객TC29김", "01012341234");
        customerService.register("김땅TC29", "01000000008");
        customerService.register("이땅TC29", "01000000007");
        customerService.register("박땅TC29", "01000000006");
        // when 이름 일부("고객") 검색 시
        mockMvc.perform(get("/api/customers").param("q", "고객TC29"))
                // then 200, totalElements 2, content 이름 = 고객TC29포함, 계좌 0원
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[*].name", everyItem(containsString("고객TC29"))))
                .andExpect(jsonPath("$.content[*].balance", everyItem(is(0))));

    }

    @Test
    @DisplayName("전화번호 뒷자리 검색 시 해당 고객만 잔액과 함께 반환")
    @Tag("TC-29")
    void search_by_phone_last_digits_returns_only_matching_customer_with_balance() throws Exception {
        // given 고객 등록
        customerService.register("김고객", "01011117979");
        customerService.register("이고객", "01022223333");
        customerService.register("박고객", "01033334444");
        // when 전화번호 뒷자리 검색 시
        mockMvc.perform(get("/api/customers").param("q", "7979"))
                // then 200, totalElements 1, content name = 김고객, 계좌 0원, maskedPhone
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].name").value("김고객"))
                .andExpect(jsonPath("$.content[0].balance").value(0))
                .andExpect(jsonPath("$.content[0].maskedPhone").value("010-****-7979"));
    }

    @Test
    @DisplayName("비활성 고객 - 기본(토글 꺼짐)에서 목록·검색·건수 제외, 토글 켜면 포함")
    @Tag("TC-35")
    void inactive_customers_are_excluded_by_default_and_included_with_toggle() throws Exception {
        // given 고객 등록(활성 3, 비활성 2)
        Employee admin = saveAdmin("tc35-admin"); // 비활성화는 ADMIN만 하므로 처리자도 ADMIN으로
        customerService.register("TC35김고객", "01012121212");
        customerService.register("TC35이고객", "01023232323");
        customerService.register("TC35박고객", "01034343434");
        RegisteredCustomer firstInactiveCustomer = registerInactiveCustomer("TC35최고객", "01045454545", admin);
        registerInactiveCustomer("TC35홍고객", "01056565656", admin);
        // when 기본 목록, 기본 검색
        mockMvc.perform(get("/api/customers").param("q", "TC35"))
                // then — 200, active=true(비활성 2명 제외) , totalElements 3
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].active", everyItem(is(true))))
                .andExpect(jsonPath("$.totalElements").value(3));

        // when 기본 목록, 비활성 고객 검색
        mockMvc.perform(get("/api/customers").param("q", firstInactiveCustomer.name()))
                // then — 200, totalElements 0
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        // when 토글 켠 목록, 토글 켠 검색
        mockMvc.perform(get("/api/customers").param("includeInactive", "true").param("q", "TC35"))
                // then — 200, totalElements 5 ,비활성 고객 검색 ok
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.active == false)]", hasSize(2)))
                .andExpect(jsonPath("$.totalElements").value(5));

        // when 토글 켠 목록, 비활성 고객 이름으로 검색
        mockMvc.perform(get("/api/customers").param("includeInactive", "true").param("q", firstInactiveCustomer.name()))
                // then — 200, totalElements 1 ,비활성 고객 이름,마스킹 번호 확인 ok
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.active == false)]", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(firstInactiveCustomer.customerId().toString()))
                .andExpect(jsonPath("$.content[0].maskedPhone").value("010-****-4545"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("고객 목록 페이지 경계 — 중복·누락 없이 전체 순회")
    @Tag("TC-36")
    void paging_through_all_pages_returns_every_customer_once() throws Exception {
        // given 고객25명 등록
        for (int i = 0; i < 25; i++) {
            customerService.register("TC36" + i + "고객", "0101212" + String.format("%04d", i)); // 전화번호 항상 11자리
        }
        entityManager.flush();
        jdbcTemplate.update("UPDATE customers SET created_at = now() WHERE name LIKE 'TC36%'"); // 등록 시각 동점으로

        // when 1 size=10으로 page 0
        String page0 = mockMvc.perform(get("/api/customers").param("q", "TC36").param("size", "10").param("page", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasNext").value(true)) // 첫 페이지는 다음 페이지가 있다
                .andReturn().getResponse().getContentAsString(); // 끝에서 본문을 꺼낸다

        List<String> page0Ids = JsonPath.read(page0, "$.content[*].id"); // id들만꺼내기

        // when 2 size=10으로 page 1
        String page1 = mockMvc.perform(get("/api/customers").param("q", "TC36").param("size", "10").param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasNext").value(true))
                .andReturn().getResponse().getContentAsString();

        List<String> page1Ids = JsonPath.read(page1, "$.content[*].id"); // id들만꺼내기

        // when 3 size=10으로 page 2
        String page2 = mockMvc.perform(get("/api/customers").param("q", "TC36").param("size", "10").param("page", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasNext").value(false))
                .andReturn().getResponse().getContentAsString();

        List<String> page2Ids = JsonPath.read(page2, "$.content[*].id"); // id들만꺼내기

        List<String> pagedIds = new ArrayList<>();
        pagedIds.addAll(page0Ids);
        pagedIds.addAll(page1Ids);
        pagedIds.addAll(page2Ids);
        // when 4 size=100으로 한 번에 전체를 받는다
        String allId = mockMvc.perform(get("/api/customers").param("q", "TC36").param("size", "100").param("page", "0"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<String> allIds = JsonPath.read(allId, "$.content[*].id");

        // then 나눠 받은 id = 한 번에 받은 id (중복·누락 없음)
        assertThat(allIds).hasSize(25);
        assertThat(pagedIds).hasSize(25).doesNotHaveDuplicates();
        assertThat(pagedIds).containsExactlyElementsOf(allIds);
    }

    @ParameterizedTest(name = "검색어 [{0}]")
    @ValueSource(strings = { "%", "_", "100%" })
    @DisplayName("검색어에 특수문자(%, _) 포함 — 오류 없이 안전 처리")
    @Tag("TC-37")
    void search_with_like_wildcards_matches_literally_without_error(String keyword) throws Exception {
        // given — 고객 5명 (이름에 %·_ 없음)
        customerService.register("김고객TC37", "01012345678");
        customerService.register("고객TC37김", "01012341234");
        customerService.register("김땅TC37", "01000000008");
        customerService.register("이땅TC37", "01000000007");
        customerService.register("박땅TC37", "01000000006");
        // when — param("q", keyword)
        mockMvc.perform(get("/api/customers").param("q", keyword))
                // then — 200, totalElements 0, content 비어 있음
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.content").isEmpty());
    }

    @Test
    @DisplayName("모든 고객에게 선결제 계좌가 1개씩 존재")
    @Tag("TC-38")
    void every_customer_has_exactly_one_prepaid_account() throws Exception {
        // given 고객 등록
        customerService.register("김고객", "01011117979");
        customerService.register("이고객", "01022223333");
        customerService.register("박고객", "01033334444");
        // when 계좌가 1개가 아닌 고객 수를 DB에서 센다
        entityManager.flush(); // JPA메모리 -> DB로 보내기
        Integer brokenCustomers = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM customers c
                WHERE (SELECT count(*) FROM prepaid_accounts a WHERE a.customer_id = c.id) <> 1
                """, Integer.class);
        // then 0명이어야 한다
        assertThat(brokenCustomers).isEqualTo(0);
    }

    @Test
    @DisplayName("충전 후 고객 목록 API에 잔액 즉시 반영")
    @Tag("TC-39")
    void customer_list_reflects_balance_right_after_charge() throws Exception {
        // given 0원 고객1명, 충전할 직원 1명
        Employee saveAdmin = saveAdmin("tc39-admin");
        RegisteredCustomer customer = customerService.register("TC39","01043435005");
        //when 1 목록 검색으로 잔액 0원 확인
        mockMvc.perform(get("/api/customers").param("q", "TC39"))
                // then(사전 확인) 충전 전 잔액 0원
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].balance").value(0))
                .andExpect(jsonPath("$.totalElements").value(1));

        //50000원 충전 ,충전은 사전 준비라 서비스로 (처리 직원 = 저장한 ADMIN)
        chargeService.charge(customer.customerId(), new BigDecimal("50000"), null, saveAdmin.getId(), IdempotencyKey.of("tc39-charge")); 
        
        //when 2 충전 후 고객 목록 다시 검색
        mockMvc.perform(get("/api/customers").param("q", "TC39"))
                // then — 200, 잔액 50000원,totalElements 1
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].balance").value(50000))
                .andExpect(jsonPath("$.totalElements").value(1));

    }

    // 고객 등록 → 바로 비활성화 (잔액 0원이라 가능). 비활성화는 사전 준비라 API 대신 서비스로
    private RegisteredCustomer registerInactiveCustomer(String name, String phone, Employee admin) {
        RegisteredCustomer customer = customerService.register(name, phone);
        customerService.deactivate(customer.customerId(), admin.getId());
        return customer;
    }

    // ADMIN 직원만 저장 (서비스로 비활성화할 때 처리자 ID용)
    private Employee saveAdmin(String loginId) {
        Employee admin = Employee.create(loginId, passwordEncoder.encode("test5678!"), "테스트사장", Role.ADMIN,
                clock.instant());
        employeeRepository.add(admin);
        return admin;
    }

}

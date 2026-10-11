package com.creditbook.customer;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.creditbook.TestcontainersConfiguration;
import com.creditbook.customer.application.CustomerService;
import com.creditbook.support.RequiresDocker;
import com.creditbook.support.WithMockEmployee;


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

    @Test
    @DisplayName("이름 일부 검색 시 해당 고객만 잔액과 함께 반환") 
    @Tag("TC-29")
    void search_by_partial_name_returns_only_matching_customers_with_balance() throws Exception{
        //  given 고객 등록
        customerService.register("김고객TC29", "01012345678");
        customerService.register("고객TC29김", "01012341234");
        customerService.register("김땅TC29", "01000000008");
        customerService.register("이땅TC29", "01000000007");
        customerService.register("박땅TC29", "01000000006");
		//  when 이름 일부("고객") 검색 시
        mockMvc.perform(get("/api/customers").param("q", "고객TC29"))
	    //  then 200, 총 검색 개수, 이름 확인, 잔액 확인
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(2))
        .andExpect(jsonPath("$.content[*].name", everyItem(containsString("고객TC29"))))
        .andExpect(jsonPath("$.content[*].balance",everyItem(is(0))));
		
    }
    
    @Test
    @DisplayName("전화번호 뒷자리 검색 시 해당 고객만 잔액과 함께 반환") 
    @Tag("TC-29")
    void search_by_phone_last_digits_returns_only_matching_customer_with_balance() throws Exception{
        //  given 고객 등록
        customerService.register("김고객", "01011117979");
        customerService.register("이고객", "01022223333");
        customerService.register("박고객", "01033334444");
        //  when 전화번호 뒷자리 검색 시
        mockMvc.perform(get("/api/customers").param("q", "7979"))
        //  then 200, 총 검색 개수, 이름 확인, 잔액 확인, 연락처 마스킹 확인
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].name").value("김고객"))
        .andExpect(jsonPath("$.content[0].balance").value(0))
        .andExpect(jsonPath("$.content[0].maskedPhone").value("010-****-7979"));
    }

    // @Test
    @DisplayName("비활성 고객은 기본(토글 꺼짐)에서 목록·검색·건수에서 제외") 
    @Tag("TC-35")
    void inactive_customers_are_excluded_from_list_search_and_count_by_default() throws Exception{
        
    }

    // @Test
    @DisplayName("고객 목록 페이지 경계 — 중복·누락 없이 전체 순회")
    @Tag("TC-36")
    void paging_through_all_pages_returns_every_customer_once() throws Exception{
        
    }

    @ParameterizedTest(name = "검색어 [{0}]")
    @ValueSource(strings = { "%", "_", "100%" })
    @DisplayName("검색어에 특수문자(%, _) 포함 — 오류 없이 안전 처리") 
    @Tag("TC-37")
    void search_with_like_wildcards_matches_literally_without_error(String keyword) throws Exception{
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

    // @Test
    @DisplayName("모든 고객에게 선결제 계좌가 1개씩 존재") 
    @Tag("TC-38")
    void every_customer_has_exactly_one_prepaid_account() throws Exception{
        
    }

    // @Test
    @DisplayName("충전 후 고객 목록 API에 잔액 즉시 반영") 
    @Tag("TC-39")
    void customer_list_reflects_balance_right_after_charge() throws Exception{
        
    }
}

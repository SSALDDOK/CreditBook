package com.creditbook.prepaid.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.creditbook.global.security.WebSecurityTestConfig;
import com.creditbook.prepaid.application.CancelService;
import com.creditbook.support.WithMockEmployee;

/**
 * CurrentEmployee 구현 빈이 없을 때. 컨텍스트는 뜨고 사용 취소 요청은 401 로 거절된다.
 */
@WebMvcTest(CancelController.class)
@Import(WebSecurityTestConfig.class)
@WithMockEmployee
@Tag("REQ-34")
class CancelControllerWithoutEmployeeTest {

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	CancelService cancelService;

	@Test
	@DisplayName("현재 직원 구현이 없으면 사용 취소 요청을 401로 거절하고 취소하지 않는다")
	void cancel_use_is_rejected_with_401_when_no_current_employee_bean() throws Exception {
		// given
		String url = "/api/customers/" + UUID.randomUUID() + "/uses/" + UUID.randomUUID() + "/cancel";

		// when / then
		mockMvc.perform(post(url).header(IdempotencyHeaders.IDEMPOTENCY_KEY, "key-0001")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"reason\": \"주문 착오\"}"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
				.andExpect(jsonPath("$.fieldErrors").isEmpty());
		verify(cancelService, never()).cancelUse(any(), any(), any(), any(), any());
	}

}

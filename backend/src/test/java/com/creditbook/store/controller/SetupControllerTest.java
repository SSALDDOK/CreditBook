package com.creditbook.store.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.creditbook.auth.domain.Role;
import com.creditbook.global.security.WebSecurityTestConfig;
import com.creditbook.store.application.StoreSetupResult;
import com.creditbook.store.application.StoreSetupService;
import com.creditbook.store.domain.InvalidStoreNameException;
import com.creditbook.store.domain.SetupAlreadyCompletedException;

/**
 * 매장 개설 API 의 요청·응답 형태 (REQ-29). 서비스는 목으로 대체하고 웹 계층(로그인 없이 호출·검증·DTO 변환·오류 응답)만 본다.
 * 로그인하지 않은 요청으로 부른다 — 보안 구성은 운영과 같다.
 */
@WebMvcTest(SetupController.class)
@Import(WebSecurityTestConfig.class)
@ExtendWith(OutputCaptureExtension.class)
@Tag("REQ-29")
class SetupControllerTest {

	private static final UUID ADMIN_ID = UUID.fromString("4b5c6d7e-8f90-4a1b-8c2d-3e4f5a6b7c8d");
	private static final String BODY = """
			{"storeName": "카페 새벽", "ownerName": "김사장", "loginId": "kim.owner",
			 "password": "s3cure-pass", "passwordConfirm": "s3cure-pass"}
			""";

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	StoreSetupService storeSetupService;

	private ResultActions setUp(String body) throws Exception {
		return mockMvc.perform(post("/api/setup").contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private static String body(String storeName, String ownerName, String loginId, String password,
			String passwordConfirm) {
		return "{\"storeName\": " + json(storeName) + ", \"ownerName\": " + json(ownerName) + ", \"loginId\": "
				+ json(loginId) + ", \"password\": " + json(password) + ", \"passwordConfirm\": " + json(passwordConfirm)
				+ "}";
	}

	private static String json(String value) {
		return value == null ? "null" : "\"" + value + "\"";
	}

	// ---- 개설 가능 여부

	@ParameterizedTest(name = "[{index}] available={0}")
	@ValueSource(booleans = { true, false })
	@DisplayName("로그인하지 않아도 개설 가능 여부를 볼 수 있고, 응답에는 가능 여부만 있다")
	void status_is_public_and_returns_only_available(boolean available) throws Exception {
		// given
		given(storeSetupService.isSetupAvailable()).willReturn(available);

		// when / then
		mockMvc.perform(get("/api/setup/status"))
				.andExpect(status().isOk())
				.andExpect(content().json("{\"available\": " + available + "}", JsonCompareMode.STRICT));
	}

	// ---- 개설 성공

	@Test
	@DisplayName("계정 0개 상태에서 로그인 없이 매장을 개설하면 201과 매장명·아이디·이름·역할만 응답하고 쿠키는 주지 않는다")
	void setup_returns_201_without_secrets_or_cookie() throws Exception {
		// given
		given(storeSetupService.setUp("카페 새벽", "김사장", "kim.owner", "s3cure-pass"))
				.willReturn(new StoreSetupResult("카페 새벽", ADMIN_ID, "kim.owner", "김사장", Role.ADMIN));

		// when / then — STRICT: 비밀번호·해시·id 같은 다른 필드가 있으면 실패
		setUp(BODY)
				.andExpect(status().isCreated())
				.andExpect(content().json("""
						{"storeName": "카페 새벽", "loginId": "kim.owner", "name": "김사장", "role": "ADMIN"}
						""", JsonCompareMode.STRICT))
				.andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
				.andExpect(header().doesNotExist(HttpHeaders.LOCATION));
	}

	// ---- 이미 개설됨

	@Test
	@DisplayName("계정이 이미 있으면 403 SETUP_ALREADY_COMPLETED 로 거절하고, 로그에는 입력값 없이 WARN 을 남긴다")
	void setup_rejected_with_403_when_already_completed(CapturedOutput output) throws Exception {
		// given
		given(storeSetupService.setUp(anyString(), anyString(), anyString(), anyString()))
				.willThrow(new SetupAlreadyCompletedException());

		// when / then
		setUp(BODY)
				.andExpect(status().isForbidden())
				.andExpect(content().json("""
						{"code": "SETUP_ALREADY_COMPLETED", "message": "이미 개설된 매장입니다. 로그인해 주세요.", "fieldErrors": []}
						""", JsonCompareMode.STRICT));
		assertThat(output.getOut().lines().filter(line -> line.contains("setup rejected: already completed")))
				.singleElement().asString().contains("WARN");
		assertThat(output.getOut()).doesNotContain("카페 새벽").doesNotContain("kim.owner").doesNotContain("김사장");
	}

	@Test
	@DisplayName("동시 요청이 매장을 먼저 만들어 늦은 요청이 막혀도 500 이 아니라 403 SETUP_ALREADY_COMPLETED 다")
	void concurrent_setup_rejected_with_403() throws Exception {
		// given — 저장소가 ux_store_profile_singleton 위반을 바꾼 예외
		given(storeSetupService.setUp(anyString(), anyString(), anyString(), anyString()))
				.willThrow(new SetupAlreadyCompletedException(new RuntimeException("duplicate key")));

		// when / then
		setUp(BODY)
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("SETUP_ALREADY_COMPLETED"));
	}

	// ---- 입력 오류

	@ParameterizedTest(name = "[{index}] {0} = \"{1}\"")
	@CsvSource(delimiter = '|', nullValues = "NULL", value = {
			"storeName | NULL",
			"storeName | ''",
			"storeName | '   '",
			"storeName | 가가가가가가가가가가가가가가가가가가가가가가가가가가가가가가가",
			"ownerName | NULL",
			"ownerName | '  '",
			"ownerName | 가가가가가가가가가가가가가가가가가가가가가가가가가가가가가가가가가가가가가가가가가가가가가가가가가가가",
			"loginId | NULL",
			"loginId | kim",
			"loginId | abcdefghij0123456789x",
			"loginId | kim-owner",
			"loginId | kim owner",
			"loginId | 김사장님",
			"password | NULL",
			"password | 1234567",
			"password | aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
			"password | 가나다라마바사아자차카타파하가나다라마바사아자차카",
			"passwordConfirm | NULL" })
	@DisplayName("항목이 규칙(매장명 1–30자·이름 1–50자·아이디 영문숫자마침표 4–20자·비밀번호 8–72자)에 맞지 않으면 400 INVALID_INPUT 의 fieldErrors 에 그 항목만 실린다")
	void invalid_field_is_rejected(String field, String value) throws Exception {
		// given — 그 항목만 바꾸고 나머지는 올바른 값 (비밀번호를 바꾸면 확인도 같은 값으로, 비밀번호가 없으면 확인은 올바른 값)
		String storeName = "storeName".equals(field) ? value : "카페 새벽";
		String ownerName = "ownerName".equals(field) ? value : "김사장";
		String loginId = "loginId".equals(field) ? value : "kim.owner";
		String password = "password".equals(field) ? value : "s3cure-pass";
		String passwordConfirm = "passwordConfirm".equals(field) ? value : (password == null ? "s3cure-pass" : password);

		// when / then
		setUp(body(storeName, ownerName, loginId, password, passwordConfirm))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"))
				// 영문 73자처럼 글자 수와 바이트 수를 함께 넘으면 같은 항목에 사유가 2개 실릴 수 있다 — 다른 항목은 섞이지 않는다
				.andExpect(jsonPath("$.fieldErrors[*].field", everyItem(equalTo(field))))
				.andExpect(jsonPath("$.fieldErrors").isNotEmpty());
		verify(storeSetupService, never()).setUp(anyString(), anyString(), anyString(), anyString());
	}

	@ParameterizedTest(name = "[{index}] {0} / {1}")
	@CsvSource(delimiter = '|', value = {
			"'  가가가가가가가가가가가가가가가가가가가가가가가가가가가가가가  ' | kim.",
			"가 | abcdefghij0123456789" })
	@DisplayName("매장명은 앞뒤 공백 제거 후 1–30자, 아이디는 4–20자 경계값이면 받는다")
	void boundary_values_are_accepted(String storeName, String loginId) throws Exception {
		// given — 비밀번호는 최소 8자·최대 72자(72바이트)
		given(storeSetupService.setUp(anyString(), anyString(), anyString(), anyString()))
				.willReturn(new StoreSetupResult("카페", ADMIN_ID, loginId, "김사장", Role.ADMIN));

		// when / then
		setUp(body(storeName, "김사장", loginId, "12345678", "12345678")).andExpect(status().isCreated());
		setUp(body(storeName, "김사장", loginId, "a".repeat(72), "a".repeat(72))).andExpect(status().isCreated());
	}

	@Test
	@DisplayName("비밀번호 확인이 비밀번호와 다르면 400 INVALID_INPUT 의 fieldErrors 에 passwordConfirm 이 실린다")
	void password_confirm_mismatch_is_rejected() throws Exception {
		// when / then
		setUp(body("카페 새벽", "김사장", "kim.owner", "s3cure-pass", "s3cure-pasz"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"))
				.andExpect(jsonPath("$.fieldErrors", hasSize(1)))
				.andExpect(jsonPath("$.fieldErrors[0].field").value("passwordConfirm"))
				.andExpect(jsonPath("$.fieldErrors[0].message").value("비밀번호 확인이 일치하지 않습니다."));
		verify(storeSetupService, never()).setUp(anyString(), anyString(), anyString(), anyString());
	}

	@Test
	@DisplayName("여러 항목이 틀리면 틀린 항목이 모두 fieldErrors 에 실린다")
	void multiple_invalid_fields_are_reported_together() throws Exception {
		// when / then
		setUp(body(" ", "김사장", "ab", "short", "other"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.fieldErrors[*].field",
						containsInAnyOrder("storeName", "loginId", "password", "passwordConfirm")));
	}

	@Test
	@DisplayName("받지 않는 항목(role)이 있으면 400 INVALID_INPUT 으로 거절한다")
	void unknown_field_is_rejected() throws Exception {
		// when / then
		setUp(BODY.replace("}", ", \"role\": \"STAFF\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"))
				.andExpect(jsonPath("$.fieldErrors[0].field").value("role"));
		verify(storeSetupService, never()).setUp(anyString(), anyString(), anyString(), anyString());
	}

	@Test
	@DisplayName("도메인이 매장명을 거절하면 400 INVALID_INPUT 의 fieldErrors 에 storeName 이 실린다")
	void domain_store_name_rejection_maps_to_invalid_input() throws Exception {
		// given — DTO 검증을 통과했지만 도메인 방어선이 거절한 경우
		given(storeSetupService.setUp(anyString(), anyString(), anyString(), anyString()))
				.willThrow(new InvalidStoreNameException("매장명을 입력해 주세요."));

		// when / then
		setUp(BODY)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"))
				.andExpect(jsonPath("$.fieldErrors[0].field").value("storeName"));
	}

}

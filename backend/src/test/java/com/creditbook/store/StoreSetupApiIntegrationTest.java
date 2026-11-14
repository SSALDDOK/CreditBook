package com.creditbook.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.creditbook.TestcontainersConfiguration;
import com.creditbook.store.domain.StoreInviteCode;
import com.creditbook.support.RequiresDocker;

/**
 * 초대 코드 가입 API 통합 테스트 (REQ-29·REQ-36, TC-25·TC-61 1·2·4·5번). 가입 화면을 숨기는 것과 API를 막는 것은 다르므로,
 * 비로그인으로 API를 직접 불러 본다. 동시 가입 상한(TC-61 3번)은 커밋이 필요해 InviteCodeConcurrencyApiIntegrationTest 에서 본다.
 * <p>
 * 이 클래스 안의 데이터는 테스트마다 롤백된다. 가입 레이트 리밋은 운영 기준(10분 5회)으로 되돌려 쓰고,
 * 테스트마다 다른 IP로 보내 다른 테스트의 요청 수에 걸리지 않게 한다.
 */
@AutoConfigureMockMvc
@RequiresDocker
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
		"creditbook.auth.signup-rate-limit.max-requests=5", // 테스트 설정(10000)을 운영 기준으로 되돌림
		"creditbook.auth.signup-rate-limit.window=10m" })
@Transactional
class StoreSetupApiIntegrationTest {

	// 초대 코드 문자 (헷갈리는 I·L·O·0·1 제외 31자, 운영 점검 가이드 10.4와 같음)
	private static final String CODE_CHARS = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	EntityManager entityManager;

	@Test
	@DisplayName("초대 코드 칸 없이 가입 API 직접 호출 — 400 INVALID_INPUT(fieldErrors에 inviteCode), 매장·계정이 생기지 않는다")
	@Tag("TC-25")
	@Tag("REQ-29")
	void signup_without_invite_code_is_rejected() throws Exception {
		// given — 요청 전 개수 (다른 테스트 클래스가 커밋한 행이 있을 수 있어 0이 아니다)
		int storesBefore = countRows("stores");
		int employeesBefore = countRows("employees");

		// when & then — inviteCode 항목을 아예 빼고 보냄
		signup("10.25.0.1", """
				{"storeName": "단골 카페", "ownerName": "이사장", "loginId": "tc25.nocode",
				 "password": "s3cure-pass", "passwordConfirm": "s3cure-pass"}
				""")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"))
				.andExpect(jsonPath("$.fieldErrors[*].field", hasItem("inviteCode")));

		assertThat(countRows("stores")).isEqualTo(storesBefore);       // 매장 안 생김
		assertThat(countRows("employees")).isEqualTo(employeesBefore); // 계정 안 생김
	}

	@Test
	@DisplayName("없는 초대 코드로 가입 API 직접 호출 — 400 INVALID_INVITE_CODE, 매장·계정이 생기지 않는다")
	@Tag("TC-25")
	@Tag("REQ-29")
	void signup_with_unknown_invite_code_is_rejected() throws Exception {
		// given — 발급한 적 없는 코드 (DB에 넣지 않음)
		int storesBefore = countRows("stores");
		int employeesBefore = countRows("employees");

		// when & then
		signup("10.25.0.2", signupBody(newRawCode(), "tc25.unknown"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INVITE_CODE"));

		assertThat(countRows("stores")).isEqualTo(storesBefore);
		assertThat(countRows("employees")).isEqualTo(employeesBefore);
	}

	@Test
	@DisplayName("요청 본문에 role 같은 받지 않는 항목을 넣으면 400 INVALID_INPUT — 사장 외 역할로 가입할 수 없다")
	@Tag("TC-25")
	@Tag("REQ-29")
	void signup_with_unknown_field_role_is_rejected() throws Exception {
		// given — 쓸 수 있는 코드까지 넣어서, 거절 이유가 코드가 아니라 role 항목임을 분명히 함
		String rawCode = newRawCode();
		UUID codeId = insertInviteCode(rawCode, 10, 0, true);
		int storesBefore = countRows("stores");

		// when & then — role: STAFF 를 끼워 넣음
		signup("10.25.0.3", """
				{"inviteCode": "%s", "storeName": "단골 카페", "ownerName": "이사장", "loginId": "tc25.role",
				 "password": "s3cure-pass", "passwordConfirm": "s3cure-pass", "role": "STAFF"}
				""".formatted(rawCode))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"))
				.andExpect(jsonPath("$.fieldErrors[0].field").value("role"));

		assertThat(countRows("stores")).isEqualTo(storesBefore);
		assertThat(usedCountOf(codeId)).isZero(); // 본문 읽기에서 막혀 코드도 안 씀
	}

	@Test
	@DisplayName("사용 가능한 초대 코드로 가입 — 201, 새 매장과 사장 계정이 생기고 코드 사용 횟수가 1 늘며, 그 아이디·비밀번호로 로그인 API가 성공한다")
	@Tag("TC-61")
	@Tag("REQ-36")
	@Tag("REQ-29")
	void signup_with_usable_invite_code_creates_store_and_owner() throws Exception {
		// given — 10번 쓸 수 있는 새 코드 (사용 0회)
		String rawCode = newRawCode();
		UUID codeId = insertInviteCode(rawCode, 10, 0, true);
		int storesBefore = countRows("stores");

		// when — 비로그인으로 가입
		signup("10.61.1.1", signupBody(rawCode, "tc61.owner"))
				.andExpect(status().isCreated())
				.andExpect(header().doesNotExist("Set-Cookie")) // 자동 로그인 없음 (쿠키 안 줌)
				.andExpect(content().json("""
						{"storeName": "단골 카페", "loginId": "tc61.owner", "name": "이사장", "role": "ADMIN"}
						""", JsonCompareMode.STRICT)); // 비밀번호·해시·id·코드는 응답에 없음

		// then — 매장 1개 늘고, 그 매장에 어느 코드로 열었는지 남음
		assertThat(countRows("stores")).isEqualTo(storesBefore + 1);
		UUID storeId = jdbcTemplate.queryForObject(
				"SELECT store_id FROM employees WHERE login_id = 'tc61.owner'", UUID.class);
		assertThat(jdbcTemplate.queryForObject("SELECT invite_code_id FROM stores WHERE id = ?", UUID.class, storeId))
				.isEqualTo(codeId);
		// 사장 계정: ADMIN, 비밀번호는 BCrypt 해시
		assertThat(jdbcTemplate.queryForObject("SELECT role FROM employees WHERE login_id = 'tc61.owner'", String.class))
				.isEqualTo("ADMIN");
		assertThat(jdbcTemplate.queryForObject(
				"SELECT password_hash FROM employees WHERE login_id = 'tc61.owner'", String.class))
				.isNotEqualTo("s3cure-pass").startsWith("$2");
		// 코드 사용 횟수 0 → 1
		assertThat(usedCountOf(codeId)).isEqualTo(1);

		// 만든 아이디·비밀번호로 로그인 API가 성공한다 (가입 직후 바로 쓸 수 있는 계정인지 끝까지 확인)
		mockMvc.perform(post("/api/auth/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"loginId": "tc61.owner", "password": "s3cure-pass"}
						"""))
				.andExpect(status().isOk());
	}

	@Test
	@DisplayName("없는·꺼진·다 쓴 초대 코드로 가입 — 셋 다 같은 400 INVALID_INVITE_CODE, 매장·계정이 생기지 않고 사용 횟수 그대로")
	@Tag("TC-61")
	@Tag("REQ-36")
	void signup_with_unknown_disabled_or_used_up_code_is_rejected_the_same_way() throws Exception {
		// given — 꺼진 코드(사용 3회), 다 쓴 코드(10/10). 없는 코드는 DB에 넣지 않음
		String disabledCode = newRawCode();
		UUID disabledId = insertInviteCode(disabledCode, 10, 3, false);
		String usedUpCode = newRawCode();
		UUID usedUpId = insertInviteCode(usedUpCode, 10, 10, true);
		int storesBefore = countRows("stores");
		int employeesBefore = countRows("employees");

		// when & then — 세 경우 모두 같은 코드·같은 문구 (어느 이유인지 응답으로 구분할 수 없음)
		String[] codes = { newRawCode(), disabledCode, usedUpCode };
		for (int i = 0; i < codes.length; i++) {
			signup("10.61.2.1", signupBody(codes[i], "tc61.reject" + i))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("INVALID_INVITE_CODE"))
					.andExpect(jsonPath("$.message").value("초대 코드를 확인해 주세요."));
		}

		assertThat(countRows("stores")).isEqualTo(storesBefore);
		assertThat(countRows("employees")).isEqualTo(employeesBefore);
		assertThat(usedCountOf(disabledId)).isEqualTo(3);  // 꺼진 코드 그대로
		assertThat(usedCountOf(usedUpId)).isEqualTo(10);   // 다 쓴 코드 그대로 (11로 넘지 않음)
	}

	@Test
	@DisplayName("가입에 쓴 초대 코드는 DB에 원문 없이 해시로만 남는다")
	@Tag("TC-61")
	@Tag("REQ-36")
	void invite_code_is_stored_only_as_hash() throws Exception {
		// given — 사람이 옮겨 적은 것처럼 소문자·공백 섞어 입력해도 같은 코드로 본다
		String rawCode = newRawCode();
		UUID codeId = insertInviteCode(rawCode, 10, 0, true);

		// when — 소문자 + 앞뒤 공백으로 가입
		signup("10.61.4.1", signupBody(" " + rawCode.toLowerCase() + " ", "tc61.hash"))
				.andExpect(status().isCreated());

		// then — 저장된 값은 SHA-256 hex 64자이고 원문(정규화한 값 포함)과 다르다
		String storedHash = jdbcTemplate.queryForObject(
				"SELECT code_hash FROM store_invite_codes WHERE id = ?", String.class, codeId);
		assertThat(storedHash).matches("^[0-9a-f]{64}$")
				.isEqualTo(StoreInviteCode.hashOf(rawCode))
				.isNotEqualTo(rawCode)
				.isNotEqualTo(StoreInviteCode.normalize(rawCode));
		// 표 어디에도 원문이 없다 (행 전체를 글자로 바꿔 찾아봄)
		Integer rowsWithRawCode = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM store_invite_codes c WHERE c::text ILIKE ?", Integer.class,
				"%" + StoreInviteCode.normalize(rawCode) + "%");
		assertThat(rowsWithRawCode).isZero();
	}

	@Test
	@DisplayName("같은 IP에서 10분 안에 가입 요청 5회 뒤 6번째는 올바른 코드라도 429 SIGNUP_RATE_LIMITED(Retry-After), 코드를 쓰지 않는다")
	@Tag("TC-61")
	@Tag("REQ-36")
	void sixth_signup_request_from_same_ip_is_rate_limited() throws Exception {
		// given — 같은 IP에서 없는 코드로 5회 (한도까지 채움, 실패한 요청도 센다)
		String ip = "10.61.5.1";
		for (int i = 1; i <= 5; i++) {
			signup(ip, signupBody(newRawCode(), "tc61.limit" + i)).andExpect(status().isBadRequest());
		}
		String rawCode = newRawCode();
		UUID codeId = insertInviteCode(rawCode, 10, 0, true);
		int storesBefore = countRows("stores");

		// when & then — 6번째: 쓸 수 있는 코드여도 429
		signup(ip, signupBody(rawCode, "tc61.limit6"))
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("SIGNUP_RATE_LIMITED"))
				.andExpect(jsonPath("$.message").value("요청이 너무 많습니다. 잠시 후 다시 시도해 주세요."))
				.andExpect(header().exists("Retry-After"));

		// 429에서 멈추므로 코드도 안 쓰고 매장도 안 생김
		assertThat(usedCountOf(codeId)).isZero();
		assertThat(countRows("stores")).isEqualTo(storesBefore);

		// 다른 IP는 그대로 가입된다 (IP별로 센다)
		signup("10.61.5.2", signupBody(rawCode, "tc61.otherip")).andExpect(status().isCreated());
	}

	// 비로그인 가입 요청 — IP를 테스트마다 다르게 줘서 레이트 리밋 횟수가 섞이지 않게 함
	private ResultActions signup(String ip, String body) throws Exception {
		return mockMvc.perform(post("/api/setup")
				.with(request -> {
					request.setRemoteAddr(ip);
					return request;
				})
				.contentType(MediaType.APPLICATION_JSON)
				.content(body));
	}

	private static String signupBody(String inviteCode, String loginId) {
		return """
				{"inviteCode": "%s", "storeName": "단골 카페", "ownerName": "이사장", "loginId": "%s",
				 "password": "s3cure-pass", "passwordConfirm": "s3cure-pass"}
				""".formatted(inviteCode, loginId);
	}

	// 운영자 발급과 같은 모양의 무작위 코드 XXXXX-XXXXX (테스트마다 달라 해시가 겹치지 않음)
	private static String newRawCode() {
		StringBuilder code = new StringBuilder();
		for (int i = 0; i < 10; i++) {
			if (i == 5) {
				code.append('-');
			}
			code.append(CODE_CHARS.charAt(ThreadLocalRandom.current().nextInt(CODE_CHARS.length())));
		}
		return code.toString();
	}

	// 운영자 발급처럼 해시만 넣는다. 꺼진 코드는 active=false 와 disabled_at 을 함께 (ck_store_invite_codes_disabled)
	private UUID insertInviteCode(String rawCode, int maxUses, int usedCount, boolean active) {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update("""
				INSERT INTO store_invite_codes (id, code_hash, label, max_uses, used_count, active, disabled_at)
				VALUES (?, ?, 'TC-61 테스트', ?, ?, ?, CASE WHEN ? THEN NULL ELSE now() END)
				""", id, StoreInviteCode.hashOf(rawCode), maxUses, usedCount, active, active);
		return id;
	}

	private int usedCountOf(UUID codeId) {
		entityManager.flush();
		return jdbcTemplate.queryForObject("SELECT used_count FROM store_invite_codes WHERE id = ?", Integer.class,
				codeId);
	}

	private int countRows(String table) {
		// JPA가 아직 DB에 보내지 않은 INSERT를 먼저 내보내야 JdbcTemplate 조회에 보인다 (롤백은 그대로)
		entityManager.flush();
		return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
	}

}

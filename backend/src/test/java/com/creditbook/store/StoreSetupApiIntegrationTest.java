package com.creditbook.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;

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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.creditbook.TestcontainersConfiguration;
import com.creditbook.auth.domain.Employee;
import com.creditbook.auth.domain.EmployeeRepository;
import com.creditbook.support.RequiresDocker;
import com.creditbook.support.TestEmployees;
import com.creditbook.support.TestStores;

/**
 * 매장 개설 API 통합 테스트 (REQ-29·TC-25). 가입 화면을 숨기는 것과 API를 막는 것은 다르므로, 비로그인으로 API를 직접 불러 본다.
 * '계정 0개' 상태가 필요해서 빈 DB 컨테이너에서 시작한다 — 동시성 테스트가 직원을 실제로 커밋해 두기 때문에
 * 같은 컨테이너를 쓰면 실행 순서에 따라 결과가 달라진다. 이 클래스 안의 데이터는 테스트마다 롤백된다.
 */
@AutoConfigureMockMvc
@RequiresDocker
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS) // 새 컨텍스트 = 새 컨테이너 = 직원 0명
@Transactional
class StoreSetupApiIntegrationTest {

	private static final String SETUP_BODY = """
			{"storeName": "단골 카페", "ownerName": "이사장", "loginId": "lizzy",
			 "password": "s3cure-pass", "passwordConfirm": "s3cure-pass"}
			""";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	EmployeeRepository employeeRepository;

	@Autowired
	PasswordEncoder passwordEncoder;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	EntityManager entityManager;

	@Autowired
	Clock clock;

	@Test
	@DisplayName("계정이 있는 상태에서 매장 개설 API 직접 호출 — 403, 계정·매장이 추가로 생기지 않고 가입 가능 여부만 false로 보인다")
	@Tag("TC-25")
	@Tag("REQ-29")
	void setup_is_rejected_when_an_account_already_exists() throws Exception {
		// given — ADMIN 계정 1개와 그 매장 (계정이 하나라도 있으면 개설을 막는지 보려고 - 초대 코드 전 규칙)
		employeeRepository.add(TestEmployees.owner(TestStores.create(jdbcTemplate), "tc25.admin",
				passwordEncoder.encode("existing-pass"), "기존사장",
				clock.instant()));

		int storesBefore = countRows("stores"); // 계정엔 매장이 필수라 0이 아니다 — 요청 전 개수와 비교한다

		// when & then — 로그인 쿠키 없이 개설 요청 → 403
		mockMvc.perform(post("/api/setup")
				.contentType(MediaType.APPLICATION_JSON)
				.content(SETUP_BODY))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("SETUP_ALREADY_COMPLETED"));

		assertThat(countRows("employees")).isEqualTo(1);     // 기존 1명 그대로 (새 ADMIN 안 생김)
		assertThat(countRows("stores")).isEqualTo(storesBefore); // 매장도 안 생김 (요청 전보다 늘지 않음)

		// when & then — 가입 가능 여부는 false만. 매장명·계정 정보 같은 다른 값은 응답에 없다 (STRICT = 필드가 더 있으면 실패)
		mockMvc.perform(get("/api/setup/status"))
				.andExpect(status().isOk())
				.andExpect(content().json("""
						{"available": false}
						""", JsonCompareMode.STRICT));
	}

	@Test
	@DisplayName("계정 0개 상태에서 매장 개설 — 201, 매장과 ADMIN 계정이 둘 다 생기고 그 계정으로 로그인된다")
	@Tag("TC-25")
	@Tag("REQ-29")
	void setup_creates_store_and_admin_when_no_account_exists() throws Exception {
		// given — 빈 DB (직원 0명, 매장 없음). 전제가 깨지면 아래 결과를 믿을 수 없으니 먼저 확인
		assertThat(countRows("employees")).isZero();
		assertThat(countRows("stores")).isZero();

		mockMvc.perform(get("/api/setup/status"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.available").value(true));

		// when — 비로그인으로 개설
		mockMvc.perform(post("/api/setup")
				.contentType(MediaType.APPLICATION_JSON)
				.content(SETUP_BODY))
				.andExpect(status().isCreated())
				.andExpect(content().json("""
						{"storeName": "단골 카페", "loginId": "lizzy", "name": "이사장", "role": "ADMIN"}
						""", JsonCompareMode.STRICT)); // 비밀번호·해시·id는 응답에 없음

		// then — 매장 1행 + ADMIN 1명이 함께 생김 (한 트랜잭션)
		entityManager.flush(); // JdbcTemplate로 직접 보기 전에 JPA가 쥐고 있는 INSERT를 내보낸다
		assertThat(jdbcTemplate.queryForObject("SELECT name FROM stores", String.class)).isEqualTo("단골 카페");
		assertThat(jdbcTemplate.queryForObject("SELECT role FROM employees WHERE login_id = 'lizzy'", String.class))
				.isEqualTo("ADMIN");
		String hash = jdbcTemplate.queryForObject(
				"SELECT password_hash FROM employees WHERE login_id = 'lizzy'", String.class);
		assertThat(hash).isNotEqualTo("s3cure-pass").startsWith("$2"); // 평문이 아니라 BCrypt 해시

		// 만든 계정으로 실제 로그인이 된다 (해시가 제대로 저장됐는지 끝까지 확인)
		mockMvc.perform(post("/api/auth/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"loginId": "lizzy", "password": "s3cure-pass"}
						"""))
				.andExpect(status().isOk());

		// 한 번 개설하면 가입 경로가 닫힌다
		mockMvc.perform(get("/api/setup/status"))
				.andExpect(jsonPath("$.available").value(false));
		mockMvc.perform(post("/api/setup")
				.contentType(MediaType.APPLICATION_JSON)
				.content(SETUP_BODY.replace("lizzy", "second.owner")))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("SETUP_ALREADY_COMPLETED"));
		assertThat(countRows("employees")).isEqualTo(1); // 두 번째 요청으로 계정이 늘지 않음
	}

	private int countRows(String table) {
		// JPA가 아직 DB에 보내지 않은 INSERT를 먼저 내보내야 JdbcTemplate 조회에 보인다 (롤백은 그대로)
		entityManager.flush();
		return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
	}

}

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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.creditbook.TestcontainersConfiguration;
import com.creditbook.auth.domain.Employee;
import com.creditbook.auth.domain.EmployeeRepository;
import com.creditbook.auth.domain.Role;
import com.creditbook.support.RequiresDocker;

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
		// given — ADMIN 계정 1개 (매장 행은 없음: 매장 행이 아니라 계정 유무로 막는지 보려고)
		employeeRepository.add(Employee.create("tc25.admin", passwordEncoder.encode("existing-pass"), "기존사장",
				Role.ADMIN, clock.instant()));

		// when & then — 로그인 쿠키 없이 개설 요청 → 403
		mockMvc.perform(post("/api/setup")
				.contentType(MediaType.APPLICATION_JSON)
				.content(SETUP_BODY))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("SETUP_ALREADY_COMPLETED"));

		assertThat(countRows("employees")).isEqualTo(1);     // 기존 1명 그대로 (새 ADMIN 안 생김)
		assertThat(countRows("store_profile")).isZero();      // 매장도 안 생김

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
		assertThat(countRows("store_profile")).isZero();

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
		assertThat(jdbcTemplate.queryForObject("SELECT name FROM store_profile", String.class)).isEqualTo("단골 카페");
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

	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED) // 이 사례만 테스트 트랜잭션 밖 — 서비스가 자기 트랜잭션에서 실패하게
	@DisplayName("동시 개설의 늦은 요청 — 계정 확인은 통과했지만 매장 행이 이미 있으면 403, 계정은 생기지 않는다")
	@Tag("TC-25")
	@Tag("REQ-29")
	void late_concurrent_setup_is_rejected_by_store_singleton_constraint() throws Exception {
		// given — 동시 요청에서 늦은 쪽이 보는 순간: 앞 요청이 매장 행을 커밋했고, 늦은 쪽은 이미 '계정 0개'를 보고 지나온 상태.
		// 동시 실행 대신 매장 행만 직접 넣어 만든다 (계정 0개라 1차 확인은 통과 → 매장 INSERT에서 DB 제약에 걸려야 함)
		jdbcTemplate.update("INSERT INTO store_profile (name) VALUES ('먼저 개설된 매장')");
		try {
			// when & then — 500이 아니라 403 (실제 PostgreSQL이 넘겨준 제약 이름으로 판별됐다는 뜻)
			mockMvc.perform(post("/api/setup")
					.contentType(MediaType.APPLICATION_JSON)
					.content(SETUP_BODY))
					.andExpect(status().isForbidden())
					.andExpect(jsonPath("$.code").value("SETUP_ALREADY_COMPLETED"));

			// 트랜잭션 밖이라 flush 없이 바로 센다 (countRows의 flush는 트랜잭션 안에서만 됨)
			assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM employees", Integer.class))
					.isZero();      // 거절된 요청으로 ADMIN이 생기지 않음 (전체 롤백)
			assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM store_profile", Integer.class))
					.isEqualTo(1);  // 먼저 있던 매장 하나뿐
		}
		finally {
			// 커밋된 데이터라 직접 지운다 — 다른 사례의 '빈 DB' 전제를 지키려고 (매장 행은 장부가 아니라 지워도 됨)
			jdbcTemplate.update("DELETE FROM store_profile");
		}
	}

	private int countRows(String table) {
		// JPA가 아직 DB에 보내지 않은 INSERT를 먼저 내보내야 JdbcTemplate 조회에 보인다 (롤백은 그대로)
		entityManager.flush();
		return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
	}

}

package com.creditbook.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.creditbook.TestcontainersConfiguration;
import com.creditbook.auth.domain.Employee;
import com.creditbook.auth.domain.EmployeeRepository;
import com.creditbook.customer.application.CustomerService;
import com.creditbook.global.security.JwtTokenService;
import com.creditbook.global.security.SessionCookies;
import com.creditbook.support.RequiresDocker;
import com.creditbook.support.TestEmployees;
import com.creditbook.support.TestStores;

import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;

// TC-54 고객 정보 수정 (REQ-3) — 변경 기록, 허용 밖 필드 400, 동시 수정 409, 같은 번호 확인, 바뀐 것 없음, 다른 매장 404
// 실제 직원을 저장하고 진짜 로그인 쿠키로 부른다 — 변경 기록의 처리 직원(changed_by)이 실제 직원 행을 가리켜야 해서
@AutoConfigureMockMvc
@RequiresDocker
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
@Tag("REQ-3")
@Tag("TC-54")
class CustomerEditApiIntegrationTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	CustomerService customerService;

	@Autowired
	EmployeeRepository employeeRepository;

	@Autowired
	PasswordEncoder passwordEncoder;

	@Autowired
	JwtTokenService jwtTokenService;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	EntityManager entityManager;

	@Autowired
	Clock clock;

	UUID storeId;

	Employee staff;

	Cookie session;

	@BeforeEach
	void setUp() {
		// 테스트마다 새 매장 + 그 매장 직원(STAFF) 로그인 — 6번: STAFF 도 수정할 수 있다
		storeId = TestStores.create(jdbcTemplate);
		staff = TestEmployees.readyStaff(storeId, "tc54-" + UUID.randomUUID().toString().substring(0, 8),
				passwordEncoder.encode("test1234!"), "TC54직원", clock.instant());
		employeeRepository.add(staff);
		session = new Cookie(SessionCookies.NAME, jwtTokenService.issue(staff.getId()).value());
	}

	@Test
	@DisplayName("잔액 5,000원 고객의 이름·연락처·메모를 STAFF 가 고치면 200, 잔액은 그대로, 변경 기록 1건에 값은 남지 않는다")
	void edit_changes_fields_keeps_balance_and_records_change_without_values() throws Exception {
		// given 고객 등록 → 5,000원 충전 → 지금 버전 받기
		UUID customerId = register("수정전이름", "01054000001");
		charge(customerId, 5000);
		long version = versionOf(customerId);
		java.time.Instant before = clock.instant(); // 기록 시각이 이 요청 사이인지 보려고

		// when 이름·연락처·메모 모두 바꾼다 (연락처는 하이픈 넣어서)
		edit(customerId, """
				{"version": %d, "name": "수정후이름", "phone": "010-5400-0002", "memo": "단골"}
				""".formatted(version))
				// then 200 — 응답은 상세와 같은 모양, 잔액 그대로, 버전 +1
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("수정후이름"))
				.andExpect(jsonPath("$.maskedPhone").value("010-****-0002"))
				.andExpect(jsonPath("$.memo").value("단골"))
				.andExpect(jsonPath("$.balance").value(5000))
				.andExpect(jsonPath("$.version").value(version + 1));

		// then DB 에도 바뀐 값 (연락처는 숫자만), 잔액·거래도 DB 에서 그대로 (응답만 믿지 않는다)
		assertThat(phoneOf(customerId)).isEqualTo("01054000002");
		assertThat(balanceOf(customerId)).isEqualTo(5000);
		assertThat(jdbcTemplate.queryForObject(
				"SELECT count(*) FROM ledger_entries e JOIN prepaid_accounts a ON a.id = e.account_id WHERE a.customer_id = ?",
				Integer.class, customerId)).isEqualTo(1); // 충전 1건뿐 — 수정이 거래를 만들지 않는다

		// then 변경 기록 1건 — 처리 직원·시각·바뀐 칸
		assertThat(changeLogCount(customerId)).isEqualTo(1);
		var log = jdbcTemplate.queryForMap(
				"SELECT changed_by, changed_at, name_changed, phone_changed, memo_changed FROM customer_change_logs WHERE customer_id = ?",
				customerId);
		assertThat(log.get("changed_by")).isEqualTo(staff.getId());
		assertThat(((java.sql.Timestamp) log.get("changed_at")).toInstant()).isBetween(before, clock.instant()); // 서버 시각
		assertThat(log).containsEntry("name_changed", true).containsEntry("phone_changed", true)
				.containsEntry("memo_changed", true);

		// when 이번엔 메모 한 칸만 바꾼다 → then 새 기록은 메모만 true (바뀐 칸만 남는지 — 셋 다 true 로 찍는 결함을 잡는다)
		edit(customerId, """
				{"version": %d, "memo": "단골2"}
				""".formatted(version + 1))
				.andExpect(status().isOk());
		assertThat(changeLogCount(customerId)).isEqualTo(2);
		assertThat(jdbcTemplate.queryForObject(
				"SELECT count(*) FROM customer_change_logs WHERE customer_id = ? AND memo_changed AND NOT name_changed AND NOT phone_changed",
				Integer.class, customerId)).isEqualTo(1);

		// then 기록 행(2건) 전체를 글자로 펼쳐도 이름·연락처·메모 값이 없다 (예전 값·새 값 모두)
		String logRows = jdbcTemplate.queryForObject(
				"SELECT string_agg(row_to_json(l)::text, ',') FROM customer_change_logs l WHERE customer_id = ?",
				String.class, customerId);
		assertThat(logRows).doesNotContain("01054000001", "01054000002", "수정전이름", "수정후이름", "단골");
	}

	@ParameterizedTest
	@ValueSource(strings = { "balance", "active", "id", "storeId" })
	@DisplayName("허용 목록 밖 항목(잔액 등)을 끼워 보내면 400 INVALID_INPUT — 고객·잔액 그대로, 변경 기록 없음")
	void edit_rejects_fields_outside_allow_list(String field) throws Exception {
		// given 잔액 5,000원 고객
		UUID customerId = register("끼워넣기", "01054000011");
		charge(customerId, 5000);
		long version = versionOf(customerId);

		// when 이름 변경에 허용 밖 항목 하나를 끼운다 (값은 그럴듯하게 아무거나)
		edit(customerId, """
				{"version": %d, "name": "바뀌면안됨", "%s": 999999}
				""".formatted(version, field))
				// then 400 — fieldErrors 에 그 항목 이름
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"))
				.andExpect(jsonPath("$.fieldErrors[0].field").value(field));

		// then 아무것도 안 바뀜 — 버전도 그대로라 같은 버전으로 다시 보내면 정상 처리된다
		assertThat(nameOf(customerId)).isEqualTo("끼워넣기");
		assertThat(balanceOf(customerId)).isEqualTo(5000);
		assertThat(changeLogCount(customerId)).isZero();
		assertThat(versionOf(customerId)).isEqualTo(version);
	}

	@Test
	@DisplayName("같은 버전으로 두 번 고치면 먼저 온 쪽 200, 늦은 쪽 409 CONCURRENT_MODIFICATION — 늦은 값은 반영되지 않고, 지금과 같은 값이어도 409")
	void edit_with_stale_version_is_rejected() throws Exception {
		// given 고객과 처음 버전 (두 화면이 같은 버전을 받아 둔 상황)
		UUID customerId = register("동시수정", "01054000021");
		long version = versionOf(customerId);

		// when 먼저 온 요청 → 200
		edit(customerId, """
				{"version": %d, "name": "먼저온이름"}
				""".formatted(version))
				.andExpect(status().isOk());

		// when 늦은 요청 — 같은(이제는 낡은) 버전으로 다른 이름 → 409
		edit(customerId, """
				{"version": %d, "name": "늦은이름"}
				""".formatted(version))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));

		// when 늦은 요청이 지금 값과 같은 이름을 보내도 버전이 낡았으면 409 (버전을 먼저 본다)
		edit(customerId, """
				{"version": %d, "name": "먼저온이름"}
				""".formatted(version))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));

		// then 먼저 온 값만 남고 기록도 1건
		assertThat(nameOf(customerId)).isEqualTo("먼저온이름");
		assertThat(changeLogCount(customerId)).isEqualTo(1);
	}

	@Test
	@DisplayName("버전 비교를 통과한 뒤 다른 요청이 먼저 저장했으면 저장 시점에 409 CONCURRENT_MODIFICATION — 값은 반영되지 않는다")
	void edit_conflicting_at_save_time_is_rejected() throws Exception {
		// given 고객 등록 — 이 트랜잭션의 JPA 캐시에 버전 0 으로 들고 있다
		UUID customerId = register("저장시점충돌", "01054000071");
		long version = versionOf(customerId);
		// 다른 요청이 먼저 고쳐서 커밋한 상황 — DB 버전만 올린다 (JPA 캐시는 옛 버전 그대로)
		jdbcTemplate.update("UPDATE customers SET version = version + 1 WHERE id = ?", customerId);

		// when 화면이 받아 둔 버전으로 수정 — 도메인 비교는 캐시(옛 버전)와 같아 통과하고,
		// 저장할 때 UPDATE … WHERE version = 옛 버전 이 0행이라 @Version 이 막는다
		edit(customerId, """
				{"version": %d, "name": "늦게저장"}
				""".formatted(version))
				// then 409 — 버전 비교로 막힌 것과 같은 코드
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));

		// then 이름 그대로 (JdbcTemplate 로 직접 — 캐시엔 바꾸다 만 값이 있다)
		assertThat(jdbcTemplate.queryForObject("SELECT name FROM customers WHERE id = ?", String.class, customerId))
				.isEqualTo("저장시점충돌");
	}

	@Test
	@DisplayName("같은 매장의 다른 고객(비활성 포함)과 같은 번호로 고치면 확인 없이 409 DUPLICATE_PHONE, 확인 표시로 다시 보내면 200 — 다른 매장 번호는 상관없다")
	void edit_phone_to_same_store_duplicate_needs_confirmation() throws Exception {
		// given 같은 매장에 비활성 고객(번호 A), 다른 매장에 고객(번호 B), 고칠 고객
		UUID inactiveSame = register("같은매장비활성", "01054000031");
		// 비활성 포함 확인용 — 실제 비활성화로 (JdbcTemplate 로 바꾸면 JPA 캐시엔 활성으로 남아 있다)
		customerService.deactivate(storeId, inactiveSame, staff.getId());
		UUID otherStore = TestStores.create(jdbcTemplate);
		customerService.register(otherStore, "다른매장고객", "01054000032");
		UUID customerId = register("번호바꿀고객", "01054000033");
		long version = versionOf(customerId);

		// when 번호 A 로, 확인 표시 없이 → 409 + 겹치는 고객 정보
		edit(customerId, """
				{"version": %d, "phone": "01054000031"}
				""".formatted(version))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("DUPLICATE_PHONE"))
				.andExpect(jsonPath("$.details.existingCustomers.length()").value(1)) // 다른 매장 고객은 안 섞인다
				.andExpect(jsonPath("$.details.existingCustomers[0].id").value(inactiveSame.toString()))
				.andExpect(jsonPath("$.details.existingCustomers[0].active").value(false));
		assertThat(phoneOf(customerId)).isEqualTo("01054000033"); // 아직 안 바뀜
		assertThat(changeLogCount(customerId)).isZero();

		// when 같은 버전 + 확인 표시로 다시 → 200, 같은 번호 고객이 둘
		edit(customerId, """
				{"version": %d, "phone": "01054000031", "confirmDuplicatePhone": true}
				""".formatted(version))
				.andExpect(status().isOk());
		assertThat(jdbcTemplate.queryForObject(
				"SELECT count(*) FROM customers WHERE store_id = ? AND phone = '01054000031'", Integer.class, storeId))
				.isEqualTo(2);
		assertThat(changeLogCount(customerId)).isEqualTo(1); // 409 는 기록을 안 남기고, 확인 뒤 저장만 1건

		// when 다른 매장 고객의 번호 B 로 → 확인 없이 200 (중복 확인은 같은 매장 안에서만)
		edit(customerId, """
				{"version": %d, "phone": "01054000032"}
				""".formatted(version + 1))
				.andExpect(status().isOk());
		assertThat(phoneOf(customerId)).isEqualTo("01054000032");
	}

	@Test
	@DisplayName("지금과 같은 값만 보내면(앞뒤 공백·하이픈만 다름) 200, 변경 기록 없고 버전 그대로")
	void edit_with_same_values_changes_nothing() throws Exception {
		// given 메모가 있는 고객
		UUID customerId = customerService.register(storeId, "그대로", "01054000041", "메모있음", false).customerId();
		long version = versionOf(customerId);

		// when 이름·메모 앞뒤 공백, 연락처 하이픈만 다르게
		edit(customerId, """
				{"version": %d, "name": "  그대로  ", "phone": "010-5400-0041", "memo": " 메모있음 "}
				""".formatted(version))
				// then 200, 버전 그대로
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.version").value(version));

		// then 기록 없음
		assertThat(changeLogCount(customerId)).isZero();
	}

	@Test
	@DisplayName("메모를 빈 문자열로 보내면 메모를 지운다 — null 이나 생략은 그대로 둔다")
	void edit_memo_empty_clears_but_null_keeps() throws Exception {
		// given 메모가 있는 고객
		UUID customerId = customerService.register(storeId, "메모지우기", "01054000081", "지울메모", false).customerId();
		long version = versionOf(customerId);

		// when memo: null → 그대로 (이름만 바뀜)
		edit(customerId, """
				{"version": %d, "name": "메모그대로", "memo": null}
				""".formatted(version))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.memo").value("지울메모"));

		// when memo: "" → 지움
		edit(customerId, """
				{"version": %d, "memo": ""}
				""".formatted(version + 1))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.memo").doesNotExist());
		entityManager.flush();
		assertThat(jdbcTemplate.queryForObject("SELECT memo FROM customers WHERE id = ?", String.class, customerId))
				.isNull();
	}

	@Test
	@DisplayName("비활성 고객도 정보를 고칠 수 있고, 고쳐도 다시 활성화되지 않는다")
	void edit_inactive_customer_stays_inactive() throws Exception {
		// given 비활성 고객 (잔액 0원이라 비활성화 가능)
		UUID customerId = register("비활성수정", "01054000091");
		customerService.deactivate(storeId, customerId, staff.getId());
		long version = versionOf(customerId); // 비활성화로 버전이 올랐다

		// when 이름 수정
		edit(customerId, """
				{"version": %d, "name": "비활성수정2"}
				""".formatted(version))
				// then 200, 여전히 비활성
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("비활성수정2"))
				.andExpect(jsonPath("$.active").value(false));
	}

	@Test
	@DisplayName("같은 번호 고객이 이미 있어도 연락처를 그대로 보내고 이름만 고치면 확인 없이 200 — 연락처가 실제로 바뀔 때만 확인한다")
	void edit_name_with_unchanged_duplicate_phone_needs_no_confirmation() throws Exception {
		// given 같은 매장에 같은 번호 고객 둘 (등록 때 확인 표시로 이미 허용된 상태)
		customerService.register(storeId, "같은번호", "01054000061", null, true);
		UUID me = customerService.register(storeId, "나", "01054000061", null, true).customerId();

		// when 화면이 연락처까지 그대로 보내고 이름만 바꾼다
		edit(me, """
				{"version": %d, "name": "나2", "phone": "010-5400-0061"}
				""".formatted(versionOf(me)))
				// then 확인 창 없이 200, 바뀐 칸은 이름만
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("나2"));
		assertThat(jdbcTemplate.queryForObject(
				"SELECT count(*) FROM customer_change_logs WHERE customer_id = ? AND name_changed AND NOT phone_changed AND NOT memo_changed",
				Integer.class, me)).isEqualTo(1);
	}

	@Test
	@DisplayName("다른 매장 고객을 고치려 하면 404 CUSTOMER_NOT_FOUND — 그 고객은 그대로")
	void edit_other_store_customer_is_not_found() throws Exception {
		// given 다른 매장 고객 (버전은 처음 값 0)
		UUID otherStore = TestStores.create(jdbcTemplate);
		UUID otherCustomer = customerService.register(otherStore, "남의고객", "01054000051").customerId();

		// when 내 매장 직원이 그 id 로 수정 → 404 (있다는 사실도 드러내지 않는다)
		edit(otherCustomer, """
				{"version": 0, "name": "바꿔보기"}
				""")
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("CUSTOMER_NOT_FOUND"));

		// then 그대로
		assertThat(nameOf(otherCustomer)).isEqualTo("남의고객");
		assertThat(changeLogCount(otherCustomer)).isZero();
	}

	// ---- 도우미 ----

	private UUID register(String name, String phone) {
		return customerService.register(storeId, name, phone).customerId();
	}

	// 충전 API 로 잔액 만들기 (요청 키는 매번 새로)
	private void charge(UUID customerId, int amount) throws Exception {
		mockMvc.perform(post("/api/customers/{id}/charges", customerId).cookie(session)
				.contentType(MediaType.APPLICATION_JSON)
				.header("Idempotency-Key", UUID.randomUUID().toString())
				.content("{\"amount\": " + amount + "}"))
				.andExpect(status().isCreated());
	}

	// 상세 조회로 지금 버전 받기 — 화면이 하는 것과 같게
	private long versionOf(UUID customerId) throws Exception {
		String body = mockMvc.perform(get("/api/customers/{id}", customerId).cookie(session))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		return Long.parseLong(body.replaceAll("(?s).*\"version\"\\s*:\\s*(\\d+).*", "$1"));
	}

	private ResultActions edit(UUID customerId, String json) throws Exception {
		return mockMvc.perform(patch("/api/customers/{id}", customerId).cookie(session)
				.contentType(MediaType.APPLICATION_JSON)
				.content(json));
	}

	// JdbcTemplate 로 읽기 전에 JPA 가 아직 안 보낸 변경을 DB 로 (롤백은 그대로)
	private String nameOf(UUID customerId) {
		entityManager.flush();
		return jdbcTemplate.queryForObject("SELECT name FROM customers WHERE id = ?", String.class, customerId);
	}

	private String phoneOf(UUID customerId) {
		entityManager.flush();
		return jdbcTemplate.queryForObject("SELECT phone FROM customers WHERE id = ?", String.class, customerId);
	}

	private int balanceOf(UUID customerId) {
		entityManager.flush();
		return jdbcTemplate.queryForObject("SELECT balance FROM prepaid_accounts WHERE customer_id = ?", Integer.class,
				customerId);
	}

	private int changeLogCount(UUID customerId) {
		entityManager.flush();
		return jdbcTemplate.queryForObject("SELECT count(*) FROM customer_change_logs WHERE customer_id = ?",
				Integer.class, customerId);
	}

}

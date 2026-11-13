package com.creditbook.store;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.creditbook.TestcontainersConfiguration;
import com.creditbook.store.domain.StoreInviteCode;
import com.creditbook.support.RequiresDocker;

/**
 * 초대 코드 동시 가입 상한 통합 테스트 (REQ-36, TC-61 3번).
 * 사용이 한 번 남은 코드로 동시에 가입하면 1개만 성공하고 매장도 1개만 생기는지 실제 PostgreSQL 로 본다.
 */
@AutoConfigureMockMvc
@RequiresDocker
@Import(TestcontainersConfiguration.class)
@SpringBootTest
// @Transactional 붙이지 않는다 — 두 요청이 각자 실제로 커밋해야 동시성이 재현됨
class InviteCodeConcurrencyApiIntegrationTest {

	// 초대 코드 문자 (헷갈리는 I·L·O·0·1 제외 31자, 운영 점검 가이드 10.4와 같음)
	private static final String CODE_CHARS = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbcTemplate;

	// TODO(사용자 작성) — TC-61 3번 본문·단언
	// @Test
	// @DisplayName("사용이 한 번 남은 초대 코드로 동시에 2번 가입하면 1개만 성공하고 매장도 1개만 생긴다")
	// @Tag("TC-61")
	// @Tag("REQ-36")
	// void concurrent_signups_with_last_use_of_code_create_only_one_store() throws Exception {
	// 	// given —
	//
	// 	// when —
	//
	// 	// then —
	// }

	// 비로그인 가입 요청을 보내고 결과를 통째로 돌려준다 (검사는 메인 스레드에서)
	private MvcResult signup(String ip, String body) throws Exception {
		return mockMvc.perform(post("/api/setup")
				.with(request -> {
					request.setRemoteAddr(ip);
					return request;
				})
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
				.andReturn();
	}

	private static String signupBody(String inviteCode, String loginId) {
		return """
				{"inviteCode": "%s", "storeName": "단골 카페", "ownerName": "이사장", "loginId": "%s",
				 "password": "s3cure-pass", "passwordConfirm": "s3cure-pass"}
				""".formatted(inviteCode, loginId);
	}

	// 운영자 발급과 같은 모양의 무작위 코드 XXXXX-XXXXX (실행마다 달라 커밋된 코드와 해시가 겹치지 않음)
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

	// 실행마다 다른 아이디 (커밋되므로 고정 아이디면 두 번째 실행부터 409)
	private static String newLoginId(String prefix) {
		return prefix + UUID.randomUUID().toString().substring(0, 8);
	}

	// 운영자 발급처럼 해시만 넣는다 (커밋됨). 한 번 남은 코드 = maxUses 10, usedCount 9
	private UUID insertInviteCode(String rawCode, int maxUses, int usedCount) {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update("""
				INSERT INTO store_invite_codes (id, code_hash, label, max_uses, used_count)
				VALUES (?, ?, 'TC-61 동시 가입 테스트', ?, ?)
				""", id, StoreInviteCode.hashOf(rawCode), maxUses, usedCount);
		return id;
	}

	private int usedCountOf(UUID codeId) {
		return jdbcTemplate.queryForObject("SELECT used_count FROM store_invite_codes WHERE id = ?", Integer.class,
				codeId);
	}

	// 이 코드로 열린 매장 수
	private int storesOpenedWith(UUID codeId) {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM stores WHERE invite_code_id = ?", Integer.class,
				codeId);
	}

}

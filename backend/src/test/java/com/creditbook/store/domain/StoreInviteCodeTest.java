package com.creditbook.store.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 매장 개설 초대 코드 (REQ-36). DB 의 CHECK 제약(ck_store_invite_codes_*)과 같은 규칙을 도메인이 먼저 지킨다.
 */
@Tag("REQ-36")
class StoreInviteCodeTest {

	private static final Instant NOW = Instant.parse("2026-11-10T01:00:00Z");
	/** SHA-256 hex (소문자 64자) 형식의 값 */
	private static final String HASH = "a".repeat(64);

	@Test
	@DisplayName("새 초대 코드는 사용 중이고 사용 횟수 0회라 매장을 열 수 있다")
	void issued_code_is_active_and_unused() {
		// when
		StoreInviteCode code = StoreInviteCode.issue(HASH, "  11월 지원 1건 ", StoreInviteCode.DEFAULT_MAX_USES, NOW);

		// then
		assertThat(code.getId()).isNotNull();
		assertThat(code.getCodeHash()).isEqualTo(HASH);
		assertThat(code.getLabel()).isEqualTo("11월 지원 1건");
		assertThat(code.getMaxUses()).isEqualTo(10);
		assertThat(code.getUsedCount()).isZero();
		assertThat(code.isActive()).isTrue();
		assertThat(code.getCreatedAt()).isEqualTo(NOW);
		assertThat(code.getDisabledAt()).isNull();
		assertThat(code.canBeUsed()).isTrue();
	}

	@ParameterizedTest(name = "[{index}] \"{0}\"")
	@NullAndEmptySource
	@ValueSource(strings = { "UPPER", "63", "65", "NOT_HEX" })
	@DisplayName("코드 해시가 소문자 hex 64자가 아니면 초대 코드를 만들 수 없다")
	void invalid_code_hash_is_rejected(String kind) {
		// given — 대문자 hex / 63자 / 65자 / hex 가 아닌 글자
		String codeHash = kind == null || kind.isEmpty() ? kind : switch (kind) {
			case "UPPER" -> "A".repeat(64);
			case "63" -> "a".repeat(63);
			case "65" -> "a".repeat(65);
			default -> "g".repeat(64);
		};

		// when / then
		assertThatThrownBy(() -> StoreInviteCode.issue(codeHash, "지원", 10, NOW))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@ParameterizedTest(name = "[{index}] \"{0}\"")
	@NullAndEmptySource
	@ValueSource(strings = { "   " })
	@DisplayName("메모가 비었거나 공백뿐이면 초대 코드를 만들 수 없다")
	void blank_label_is_rejected(String label) {
		assertThatThrownBy(() -> StoreInviteCode.issue(HASH, label, 10, NOW))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("메모는 앞뒤 공백을 빼고 100자까지만 받는다")
	void label_length_boundary() {
		assertThat(StoreInviteCode.issue(HASH, "가".repeat(100), 10, NOW).getLabel()).hasSize(100);
		assertThatThrownBy(() -> StoreInviteCode.issue(HASH, "가".repeat(101), 10, NOW))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@ParameterizedTest(name = "[{index}] max_uses={0}")
	@ValueSource(ints = { 0, -1 })
	@DisplayName("사용 한도가 1회 미만이면 초대 코드를 만들 수 없다")
	void max_uses_below_one_is_rejected(int maxUses) {
		assertThatThrownBy(() -> StoreInviteCode.issue(HASH, "지원", maxUses, NOW))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("사용 한도 1회 코드도 만들 수 있다")
	void max_uses_of_one_is_accepted() {
		assertThat(StoreInviteCode.issue(HASH, "지원", 1, NOW).canBeUsed()).isTrue();
	}

	@Test
	@DisplayName("만든 시각이 없으면 초대 코드를 만들 수 없다")
	void null_created_at_is_rejected() {
		assertThatThrownBy(() -> StoreInviteCode.issue(HASH, "지원", 10, null))
				.isInstanceOf(NullPointerException.class);
	}

}

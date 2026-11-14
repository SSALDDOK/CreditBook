package com.creditbook.store.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 매장 개설 초대 코드 (REQ-36). DB 의 CHECK 제약(ck_store_invite_codes_*)과 같은 규칙을 도메인이 먼저 지킨다.
 * 입력한 코드의 정규화(공백·하이픈 제거, 대문자)와 해시(UTF-8 SHA-256 소문자 hex)도 여기서 본다.
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

	// ---- 입력한 코드 정규화·해시

	static Stream<Arguments> normalizeCases() {
		return Stream.of(
				Arguments.of("abcd-efgh", "ABCDEFGH"),          // 하이픈 제거 + 대문자
				Arguments.of("  AbCd EfGh  ", "ABCDEFGH"),      // 앞뒤·가운데 공백
				Arguments.of("ab\tcd\r\nef", "ABCDEF"),         // 탭·줄바꿈
				Arguments.of("AB\u3000CD\u00A0EF", "ABCDEF"),   // 전각 공백·줄바꿈 없는 공백
				Arguments.of("a-b - c", "ABC"),
				Arguments.of("ABCDEFGH", "ABCDEFGH"),           // 이미 정규화된 값은 그대로
				Arguments.of("가나-다", "가나다"),                // 문자 집합을 가정하지 않는다
				Arguments.of("A_B.C", "A_B.C"),                 // 하이픈·공백 말고는 건드리지 않는다
				Arguments.of(" - - ", ""),                      // 공백·하이픈뿐이면 빈 값
				Arguments.of("", ""));
	}

	@ParameterizedTest(name = "[{index}] \"{0}\" → \"{1}\"")
	@MethodSource("normalizeCases")
	@DisplayName("입력한 초대 코드는 공백·하이픈을 빼고 대문자로 바꿔 비교한다")
	void normalize_removes_spaces_and_hyphens_and_uppercases(String raw, String expected) {
		// when
		String normalized = StoreInviteCode.normalize(raw);

		// then
		assertThat(normalized).isEqualTo(expected);
	}

	@Test
	@DisplayName("띄어쓰기·하이픈·대소문자만 다른 입력은 같은 해시가 된다")
	void hash_ignores_spaces_hyphens_and_case() {
		// when
		String canonical = StoreInviteCode.hashOf("ABCDEFGH");

		// then
		assertThat(StoreInviteCode.hashOf("abcd-efgh")).isEqualTo(canonical);
		assertThat(StoreInviteCode.hashOf(" Abcd Efgh ")).isEqualTo(canonical);
		assertThat(StoreInviteCode.hashOf("ABCD-EFGI")).isNotEqualTo(canonical);
	}

	@ParameterizedTest(name = "[{index}] \"{0}\"")
	@MethodSource("hashVectors")
	@DisplayName("해시는 정규화한 값의 UTF-8 바이트 SHA-256 을 소문자 hex 64자로 나타낸 값이다 (pepper 없음)")
	void hash_is_sha256_hex_of_normalized_utf8(String raw, String expectedHex) {
		// when
		String hash = StoreInviteCode.hashOf(raw);

		// then — 저장 형식(ck_store_invite_codes_code_hash)과 같아 그대로 발급에 쓸 수 있다
		assertThat(hash).isEqualTo(expectedHex).matches("^[0-9a-f]{64}$");
		assertThat(StoreInviteCode.issue(hash, "지원", 1, NOW).getCodeHash()).isEqualTo(hash);
	}

	static Stream<Arguments> hashVectors() {
		return Stream.of(
				Arguments.of("abc", "b5d4045c3f466fa91fe2cc6abe79232a1a57cdf104f7a26e716e0a1e2789df78"),   // SHA-256("ABC")
				Arguments.of("가나-다", "29205b39ec5e51163b59abe55c48c1aa0c52fb2868cab35e23f9265e473771f4"),  // SHA-256("가나다" UTF-8)
				Arguments.of(" - ", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"));   // SHA-256("")
	}

	@Test
	@DisplayName("코드 길이를 가정하지 않는다 — 긴 입력도 64자 해시가 된다")
	void long_code_is_hashed() {
		assertThat(StoreInviteCode.hashOf("x".repeat(1000))).hasSize(64);
	}

	@Test
	@DisplayName("코드가 null 이면 정규화·해시를 하지 않는다")
	void null_code_is_rejected() {
		assertThatThrownBy(() -> StoreInviteCode.normalize(null)).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> StoreInviteCode.hashOf(null)).isInstanceOf(NullPointerException.class);
	}

}

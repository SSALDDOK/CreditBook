package com.creditbook.store.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 매장의 매장명 규칙 (REQ-29 경계 조건: 앞뒤 공백 제거 후 1–30자).
 */
@Tag("REQ-29")
class StoreTest {

	private static final Instant OPENED_AT = Instant.parse("2026-10-30T01:00:00Z");
	private static final UUID INVITE_CODE_ID = UUID.fromString("0f1e2d3c-4b5a-4968-8776-655443322110");

	@Test
	@DisplayName("매장을 열면 매장명·개설 시각과 가입에 쓴 초대 코드가 기록된다")
	void open_records_name_and_time() {
		// when
		Store store = Store.open("카페 새벽", INVITE_CODE_ID, OPENED_AT);

		// then
		assertThat(store.getId()).isNotNull();
		assertThat(store.getName()).isEqualTo("카페 새벽");
		assertThat(store.getCreatedAt()).isEqualTo(OPENED_AT);
		assertThat(store.getUpdatedAt()).isEqualTo(OPENED_AT);
		assertThat(store.getInviteCodeId()).isEqualTo(INVITE_CODE_ID);
	}

	@Test
	@DisplayName("매장명은 앞뒤 공백을 빼고 저장된다")
	void name_is_stripped() {
		// when
		Store store = Store.open("  카페 새벽 \t", INVITE_CODE_ID, OPENED_AT);

		// then
		assertThat(store.getName()).isEqualTo("카페 새벽");
	}

	@ParameterizedTest(name = "[{index}] {0}자")
	@ValueSource(ints = { 1, 30 })
	@DisplayName("매장명은 앞뒤 공백 제거 후 1–30자이면 저장된다")
	void name_within_length_is_accepted(int length) {
		// given — 앞뒤 공백은 글자 수에 들어가지 않는다
		String name = "  " + "가".repeat(length) + "  ";

		// when
		Store store = Store.open(name, INVITE_CODE_ID, OPENED_AT);

		// then
		assertThat(store.getName()).hasSize(length);
	}

	@Test
	@DisplayName("매장명이 앞뒤 공백 제거 후 31자이면 매장을 열 수 없다")
	void name_over_30_is_rejected() {
		assertThatThrownBy(() -> Store.open("가".repeat(31), INVITE_CODE_ID, OPENED_AT))
				.isInstanceOf(InvalidStoreNameException.class)
				.hasMessage("매장명은 30자 이하여야 합니다.");
	}

	@Test
	@DisplayName("매장명 글자 수는 이모지 같은 보충 문자도 한 글자로 센다")
	void name_length_counts_code_points() {
		// given — 😀 는 UTF-16 으로 2칸이지만 한 글자
		String thirtyEmoji = "😀".repeat(30);

		// when / then
		assertThat(Store.open(thirtyEmoji, INVITE_CODE_ID, OPENED_AT).getName()).isEqualTo(thirtyEmoji);
		assertThatThrownBy(() -> Store.open(thirtyEmoji + "😀", INVITE_CODE_ID, OPENED_AT))
				.isInstanceOf(InvalidStoreNameException.class);
	}

	@ParameterizedTest(name = "[{index}] \"{0}\"")
	@NullAndEmptySource
	@ValueSource(strings = { "   ", "\t" })
	@DisplayName("매장명이 비었거나 공백뿐이면 매장을 열 수 없다")
	void blank_name_is_rejected(String blank) {
		assertThatThrownBy(() -> Store.open(blank, INVITE_CODE_ID, OPENED_AT))
				.isInstanceOf(InvalidStoreNameException.class)
				.hasMessage("매장명을 입력해 주세요.");
	}

	@Test
	@DisplayName("개설 시각이 없으면 매장을 열 수 없다")
	void null_time_is_rejected() {
		assertThatThrownBy(() -> Store.open("카페 새벽", INVITE_CODE_ID, null))
				.isInstanceOf(NullPointerException.class);
	}

	@Test
	@Tag("REQ-36")
	@DisplayName("초대 코드 없이는 매장을 열 수 없다")
	void null_invite_code_is_rejected() {
		assertThatThrownBy(() -> Store.open("카페 새벽", null, OPENED_AT))
				.isInstanceOf(NullPointerException.class)
				.hasMessageContaining("inviteCodeId");
	}

	@Test
	@Tag("REQ-36")
	@DisplayName("초대 코드 예외는 없음·꺼짐·소진을 구분하지 않는 화면 안내 문구를 갖는다")
	void invalid_invite_code_has_message() {
		// when
		InvalidInviteCodeException ex = new InvalidInviteCodeException();

		// then
		assertThat(ex).hasMessage("초대 코드를 확인해 주세요.");
	}

}

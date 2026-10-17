package com.creditbook.customer.domain;

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
 * 고객 메모 규칙 (REQ-1 — 메모는 선택 입력이며 200자 이하). 스프링 컨텍스트 없이 순수 객체로 검증한다.
 */
@Tag("REQ-1")
class CustomerMemoTest {

	private static final Instant NOW = Instant.parse("2026-10-18T01:10:00Z");

	@Test
	@DisplayName("메모를 입력해 등록하면 메모가 함께 저장된다")
	void register_saves_memo() {
		// given
		String memo = "단골, 아메리카노 선호";

		// when
		Customer customer = Customer.register("박서준", "01033814821", memo, NOW);

		// then
		assertThat(customer.getMemo()).isEqualTo("단골, 아메리카노 선호");
	}

	@Test
	@DisplayName("메모의 앞뒤 공백은 지우고 저장한다")
	void register_strips_memo() {
		// given
		String padded = "  단골 \t";

		// when
		Customer customer = Customer.register("박서준", "01033814821", padded, NOW);

		// then
		assertThat(customer.getMemo()).isEqualTo("단골");
	}

	@ParameterizedTest(name = "[{index}] 메모 \"{0}\"")
	@NullAndEmptySource
	@ValueSource(strings = { " ", "   ", "\t\n", "　" })
	@DisplayName("메모는 선택 입력이며, 없거나 공백뿐이면 메모 없음으로 등록된다")
	void register_treats_blank_memo_as_none(String memo) {
		// given: null, 빈 문자열, 공백·탭·줄바꿈·전각 공백

		// when
		Customer customer = Customer.register("박서준", "01033814821", memo, NOW);

		// then
		assertThat(customer.getMemo()).isNull();
	}

	@Test
	@DisplayName("메모 없이 등록하는 기존 방식은 메모 없음으로 등록된다")
	void register_without_memo_argument_has_no_memo() {
		// when
		Customer customer = Customer.register("박서준", "01033814821", NOW);

		// then
		assertThat(customer.getMemo()).isNull();
	}

	@ParameterizedTest(name = "[{index}] {0}자")
	@ValueSource(ints = { 1, 199, 200 })
	@DisplayName("메모는 200자 이하면 등록된다")
	void register_accepts_memo_within_200_chars(int length) {
		// given
		String memo = "메".repeat(length);

		// when
		Customer customer = Customer.register("박서준", "01033814821", memo, NOW);

		// then
		assertThat(customer.getMemo()).isEqualTo(memo);
	}

	@ParameterizedTest(name = "[{index}] {0}자")
	@ValueSource(ints = { 201, 300 })
	@DisplayName("메모가 200자를 넘으면 등록이 거절된다")
	void register_rejects_memo_over_200_chars(int length) {
		// given
		String memo = "메".repeat(length);

		// when / then
		assertThatThrownBy(() -> Customer.register("박서준", "01033814821", memo, NOW))
				.isInstanceOf(InvalidCustomerMemoException.class)
				.hasMessage("메모는 200자 이하여야 합니다.");
	}

	@Test
	@DisplayName("메모 길이는 앞뒤 공백을 지운 뒤 문자 수로 센다")
	void memo_length_is_counted_after_strip_in_code_points() {
		// given: 공백 포함 204자지만 지우면 200자 / 서로게이트 쌍 문자 200개(UTF-16 으로는 400)
		String padded = "  " + "메".repeat(200) + "  ";
		String emoji = "😀".repeat(200);

		// when
		Customer paddedCustomer = Customer.register("박서준", "01033814821", padded, NOW);
		Customer emojiCustomer = Customer.register("박서준", "01033814821", emoji, NOW);

		// then
		assertThat(paddedCustomer.getMemo()).isEqualTo("메".repeat(200));
		assertThat(emojiCustomer.getMemo()).isEqualTo(emoji);
	}

	@Test
	@DisplayName("서로게이트 쌍 문자도 201개면 등록이 거절된다")
	void memo_over_200_code_points_is_rejected() {
		// given
		String emoji = "😀".repeat(201);

		// when / then
		assertThatThrownBy(() -> Customer.register("박서준", "01033814821", emoji, NOW))
				.isInstanceOf(InvalidCustomerMemoException.class);
	}

}

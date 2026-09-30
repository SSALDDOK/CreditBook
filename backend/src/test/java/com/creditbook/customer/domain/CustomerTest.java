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
 * 고객 등록 도메인 규칙. 스프링 컨텍스트 없이 순수 객체로 검증한다.
 */
@Tag("REQ-1")
class CustomerTest {

	private static final Instant NOW = Instant.parse("2026-09-30T01:00:00Z");

	@Test
	@DisplayName("이름과 연락처를 입력해 등록하면 활성 고객이 만들어지고 등록 시각이 기록된다")
	void register_creates_active_customer_with_registered_time() {
		// given
		String name = "김단골";
		String phone = "01012345678";

		// when
		Customer customer = Customer.register(name, phone, NOW);

		// then
		assertThat(customer.getId()).isNotNull();
		assertThat(customer.getName()).isEqualTo("김단골");
		assertThat(customer.getPhone()).isEqualTo("01012345678");
		assertThat(customer.isActive()).isTrue();
		assertThat(customer.getMemo()).isNull();
		assertThat(customer.getCreatedAt()).isEqualTo(NOW);
		assertThat(customer.getUpdatedAt()).isEqualTo(NOW);
	}

	@Test
	@DisplayName("이름의 앞뒤 공백은 지우고 저장한다")
	void register_strips_name() {
		// given
		String paddedName = "  김단골 ";

		// when
		Customer customer = Customer.register(paddedName, null, NOW);

		// then
		assertThat(customer.getName()).isEqualTo("김단골");
	}

	@Test
	@DisplayName("연락처 없이도 등록할 수 있다")
	void register_allows_missing_phone() {
		// given
		String phone = null;

		// when
		Customer customer = Customer.register("김단골", phone, NOW);

		// then
		assertThat(customer.getPhone()).isNull();
	}

	@ParameterizedTest(name = "[{index}] 연락처 \"{0}\"")
	@ValueSource(strings = { "021234567", "0212345678", "01012345678" })
	@DisplayName("연락처는 숫자 9–11자리면 등록된다")
	void register_accepts_phone_with_9_to_11_digits(String phone) {
		// given: 경계값 9자리·10자리·11자리

		// when
		Customer customer = Customer.register("김단골", phone, NOW);

		// then
		assertThat(customer.getPhone()).isEqualTo(phone);
	}

	@ParameterizedTest(name = "[{index}] 연락처 \"{0}\"")
	@ValueSource(strings = { "01234567", "010123456789", "010-1234-5678", "010 1234 5678", "0101234567a",
			"０１０１２３４５６７８", "", " " })
	@DisplayName("연락처가 숫자 9–11자리가 아니면 등록이 거절된다")
	void register_rejects_phone_not_9_to_11_digits(String phone) {
		// given: 8자리·12자리, 하이픈·공백·문자·전각 숫자 포함, 빈 문자열

		// when / then
		assertThatThrownBy(() -> Customer.register("김단골", phone, NOW))
				.isInstanceOf(InvalidPhoneNumberException.class)
				.hasMessage("연락처는 숫자 9–11자리여야 합니다.");
	}

	@ParameterizedTest(name = "[{index}] 이름 \"{0}\"")
	@NullAndEmptySource
	@ValueSource(strings = { " ", "   ", "\t", "　" })
	@DisplayName("이름이 비어 있거나 공백뿐이면 등록이 거절된다")
	void register_rejects_blank_name(String name) {
		// given: null, 빈 문자열, 공백·탭·전각 공백

		// when / then
		assertThatThrownBy(() -> Customer.register(name, "01012345678", NOW))
				.isInstanceOf(InvalidCustomerNameException.class)
				.hasMessage("이름을 입력해 주세요.");
	}

	@ParameterizedTest(name = "[{index}] {0}자")
	@ValueSource(ints = { 1, 20 })
	@DisplayName("이름은 1자 이상 20자 이하면 등록된다")
	void register_accepts_name_within_20_chars(int length) {
		// given
		String name = "가".repeat(length);

		// when
		Customer customer = Customer.register(name, null, NOW);

		// then
		assertThat(customer.getName()).hasSize(length);
	}

	@Test
	@DisplayName("이름이 20자를 넘으면 등록이 거절된다")
	void register_rejects_name_over_20_chars() {
		// given
		String name = "가".repeat(21);

		// when / then
		assertThatThrownBy(() -> Customer.register(name, null, NOW))
				.isInstanceOf(InvalidCustomerNameException.class)
				.hasMessage("이름은 20자 이하여야 합니다.");
	}

	@Test
	@DisplayName("이름 길이는 앞뒤 공백을 지운 뒤 문자 수로 센다")
	void name_length_is_counted_after_strip_in_code_points() {
		// given: 공백 포함 22자지만 공백을 지우면 20자 / 서로게이트 쌍 문자 20개(UTF-16 으로는 40)
		String padded = " " + "가".repeat(20) + " ";
		String emoji = "😀".repeat(20);

		// when
		Customer strippedToLimit = Customer.register(padded, null, NOW);
		Customer surrogatePairs = Customer.register(emoji, null, NOW);

		// then
		assertThat(strippedToLimit.getName()).isEqualTo("가".repeat(20));
		assertThat(surrogatePairs.getName()).isEqualTo(emoji);
	}

	@Test
	@DisplayName("등록 시각 없이는 고객을 만들 수 없다")
	void register_requires_registered_time() {
		// when / then
		assertThatThrownBy(() -> Customer.register("김단골", null, null))
				.isInstanceOf(NullPointerException.class);
	}

}

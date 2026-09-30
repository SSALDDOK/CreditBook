package com.creditbook.customer.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 연락처 마스킹 규칙. 번호는 모두 가상 번호다.
 */
@Tag("REQ-26")
class PhoneNumberMaskerTest {

	@ParameterizedTest(name = "[{index}] {0} → {1}")
	@CsvSource({
			// 11자리 — 휴대전화
			"01012345678, 010-****-5678",
			// 10자리 — 서울(02) 2-4-4, 그 밖 3-3-4
			"0212345678,  02-****-5678",
			"0311234567,  031-***-4567",
			"0161234567,  016-***-4567",
			// 9자리 — 서울(02) 2-3-4, 그 밖 3-2-4
			"021234567,   02-***-4567",
			"031234567,   031-**-4567" })
	@DisplayName("연락처는 앞자리와 뒤 4자리만 보이고 가운데 자리는 가려진다")
	void mask_shows_prefix_and_last_four_digits(String phone, String expected) {
		// given: 저장 형식(숫자 9–11자리) 연락처

		// when
		String masked = PhoneNumberMasker.mask(phone);

		// then
		assertThat(masked).isEqualTo(expected);
		assertThat(masked).doesNotContain(phone);
	}

	@ParameterizedTest(name = "[{index}] \"{0}\"")
	@NullAndEmptySource
	@DisplayName("연락처가 없으면 예외 없이 그대로 돌려준다")
	void mask_returns_missing_phone_as_is(String phone) {
		// when
		String masked = PhoneNumberMasker.mask(phone);

		// then
		assertThat(masked).isEqualTo(phone);
	}

	@ParameterizedTest(name = "[{index}] \"{0}\"")
	@ValueSource(strings = { "1", "5678", "01012345", "010123456789", "010-1234-5678", "0101234567a", " " })
	@DisplayName("저장 형식이 아닌 연락처는 예외 없이 모든 글자를 가린다")
	void mask_hides_every_character_of_unexpected_format(String phone) {
		// when
		String masked = PhoneNumberMasker.mask(phone);

		// then
		assertThat(masked).isEqualTo("*".repeat(phone.length()));
	}

}

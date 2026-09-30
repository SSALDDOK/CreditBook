package com.creditbook.customer.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.creditbook.customer.domain.CustomerSearchKeyword.Type;

/**
 * 검색어 해석 규칙. 검색창 하나로 이름 일부와 연락처 뒷자리를 모두 찾는다.
 */
@Tag("REQ-2")
class CustomerSearchKeywordTest {

	@ParameterizedTest(name = "[{index}] \"{0}\"")
	@NullAndEmptySource
	@ValueSource(strings = { " ", "   ", "\t" })
	@DisplayName("검색어가 없으면 전체 목록을 조회한다")
	void blank_query_means_no_filter(String query) {
		// when
		CustomerSearchKeyword keyword = CustomerSearchKeyword.parse(query);

		// then
		assertThat(keyword.type()).isEqualTo(Type.NONE);
		assertThat(keyword.isEmpty()).isTrue();
		assertThat(keyword.value()).isEmpty();
	}

	@ParameterizedTest(name = "[{index}] \"{0}\" → {1}")
	@CsvSource({
			"5678,          5678",
			"1,             1",
			"' 5678 ',      5678",
			"010-1234-5678, 01012345678",
			"1234-5678,     12345678",
			"'1234 5678',   12345678",
			"01012345678,   01012345678" })
	@DisplayName("숫자만 입력하면(하이픈·공백 제외) 연락처 뒷자리 검색으로 해석하고 숫자만 남긴다")
	void digits_only_query_means_phone_suffix(String query, String expectedDigits) {
		// when
		CustomerSearchKeyword keyword = CustomerSearchKeyword.parse(query);

		// then
		assertThat(keyword.type()).isEqualTo(Type.PHONE_SUFFIX);
		assertThat(keyword.value()).isEqualTo(expectedDigits);
	}

	@ParameterizedTest(name = "[{index}] \"{0}\" → \"{1}\"")
	@CsvSource({
			"김,         김",
			"단골,       단골",
			"' 김단골 ', 김단골",
			"김단골2,    김단골2",
			"Tom,        Tom",
			"'김 단골',  '김 단골'",
			"-,          -",
			"50%,        50%" })
	@DisplayName("숫자가 아닌 글자가 섞이면 이름 부분 일치 검색으로 해석하고 앞뒤 공백만 지운다")
	void other_query_means_name_contains(String query, String expectedName) {
		// when
		CustomerSearchKeyword keyword = CustomerSearchKeyword.parse(query);

		// then
		assertThat(keyword.type()).isEqualTo(Type.NAME);
		assertThat(keyword.value()).isEqualTo(expectedName);
	}

	@Test
	@DisplayName("검색어 값은 문자열 표현에 드러나지 않는다 (로그 노출 방지)")
	void to_string_hides_value() {
		// given
		CustomerSearchKeyword keyword = CustomerSearchKeyword.parse("김단골");

		// when
		String text = keyword.toString();

		// then
		assertThat(text).doesNotContain("김단골").contains("NAME");
	}

	@Test
	@DisplayName("같은 해석 결과는 같은 검색어로 본다")
	void equal_when_same_type_and_value() {
		// when / then
		assertThat(CustomerSearchKeyword.parse("010-1234")).isEqualTo(CustomerSearchKeyword.parse("0101234"));
		assertThat(CustomerSearchKeyword.parse("김")).isNotEqualTo(CustomerSearchKeyword.parse("이"));
		assertThat(CustomerSearchKeyword.parse(null)).isEqualTo(CustomerSearchKeyword.parse("  "));
	}

}

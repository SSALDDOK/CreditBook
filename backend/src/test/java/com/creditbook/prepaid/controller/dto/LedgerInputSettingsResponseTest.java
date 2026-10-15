package com.creditbook.prepaid.controller.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.creditbook.prepaid.application.MemoPresetProperties;
import com.creditbook.prepaid.domain.ChargePolicy;
import com.creditbook.prepaid.domain.LedgerEntry;

import tools.jackson.databind.json.JsonMapper;

/**
 * 화면 입력 설정 응답 — 한도는 소수점·지수 표기 없는 JSON 정수로 나간다.
 */
@Tag("REQ-6")
@Tag("REQ-31")
class LedgerInputSettingsResponseTest {

	@ParameterizedTest(name = "[{index}] 설정값 {0}")
	@ValueSource(strings = { "300000", "300000.00", "3E+5" })
	@DisplayName("1회 충전 한도는 설정 표기와 관계없이 소수점 없는 정수로 내보낸다")
	void charge_max_amount_is_written_as_plain_integer(String configured) {
		// given
		ChargePolicy policy = new ChargePolicy(new BigDecimal(configured));
		MemoPresetProperties memoPresets = new MemoPresetProperties(List.of("음료", "기타"), List.of("현금", "카드"));

		// when
		LedgerInputSettingsResponse response = LedgerInputSettingsResponse.of(policy, memoPresets);
		String json = JsonMapper.builder().build().writeValueAsString(response);

		// then
		assertThat(response.chargeMaxAmount()).isEqualByComparingTo("300000");
		assertThat(json).isEqualTo("{\"chargeMaxAmount\":300000,\"chargeMemoPresets\":[\"현금\",\"카드\"],"
				+ "\"useMemoPresets\":[\"음료\",\"기타\"],\"memoMaxLength\":" + LedgerEntry.MEMO_MAX_LENGTH + "}");
	}

}

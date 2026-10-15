package com.creditbook.prepaid.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.context.properties.bind.validation.ValidationBindHandler;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.validation.annotation.Validated;
import org.springframework.validation.beanvalidation.SpringValidatorAdapter;

import com.creditbook.prepaid.domain.LedgerEntry;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;

/**
 * 메모 문구 칩 설정({@code creditbook.memo.*}) 바인딩·검증. 스프링 컨텍스트 없이 Binder 로 기동 시와 같은 검증을 돌린다.
 */
@Tag("REQ-31")
class MemoPresetPropertiesTest {

	private static final String TOO_LONG = "가".repeat(LedgerEntry.MEMO_MAX_LENGTH + 1);

	private static ValidatorFactory validatorFactory;

	@BeforeAll
	static void createValidator() {
		validatorFactory = Validation.buildDefaultValidatorFactory();
	}

	@AfterAll
	static void closeValidator() {
		validatorFactory.close();
	}

	@Test
	@DisplayName("기동 시 검증되도록 @Validated 가 붙어 있다")
	void properties_are_validated_on_startup() {
		assertThat(MemoPresetProperties.class).hasAnnotation(Validated.class);
	}

	@Test
	@DisplayName("사용·충전 문구 목록을 설정 순서대로 읽는다")
	void binds_presets_in_order() {
		// given
		Map<String, String> source = new LinkedHashMap<>();
		source.put("creditbook.memo.presets[0]", "음료");
		source.put("creditbook.memo.presets[1]", "베이커리");
		source.put("creditbook.memo.charge-presets[0]", "현금");
		source.put("creditbook.memo.charge-presets[1]", "카드");
		source.put("creditbook.memo.charge-presets[2]", "계좌이체");

		// when
		MemoPresetProperties properties = bind(source);

		// then
		assertThat(properties.presets()).containsExactly("음료", "베이커리");
		assertThat(properties.chargePresets()).containsExactly("현금", "카드", "계좌이체");
	}

	@Test
	@DisplayName("항목 길이가 메모 최대 길이와 같으면 받는다")
	void accepts_preset_of_max_length() {
		// given
		String maxLength = "가".repeat(LedgerEntry.MEMO_MAX_LENGTH);
		Map<String, String> source = Map.of(
				"creditbook.memo.presets[0]", maxLength,
				"creditbook.memo.charge-presets[0]", "현금");

		// when
		MemoPresetProperties properties = bind(source);

		// then
		assertThat(properties.presets()).containsExactly(maxLength);
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@MethodSource("invalidSources")
	@DisplayName("문구 목록이 없거나 비었거나, 항목이 공백이거나 메모 최대 길이를 넘으면 기동을 멈춘다")
	void invalid_presets_fail(String description, Map<String, String> source) {
		assertThatThrownBy(() -> bind(source))
				.isInstanceOf(BindException.class)
				.hasRootCauseInstanceOf(BindValidationException.class);
	}

	static Stream<Arguments> invalidSources() {
		return Stream.of(
				Arguments.of("사용 문구 없음",
						Map.of("creditbook.memo.charge-presets[0]", "현금")),
				Arguments.of("충전 문구 없음",
						Map.of("creditbook.memo.presets[0]", "음료")),
				Arguments.of("충전 문구 빈 목록",
						Map.of("creditbook.memo.presets[0]", "음료", "creditbook.memo.charge-presets", "")),
				Arguments.of("사용 문구 항목 공백",
						Map.of("creditbook.memo.presets[0]", "  ", "creditbook.memo.charge-presets[0]", "현금")),
				Arguments.of("충전 문구 항목 공백",
						Map.of("creditbook.memo.presets[0]", "음료", "creditbook.memo.charge-presets[0]", "")),
				Arguments.of("사용 문구 항목 길이 초과",
						Map.of("creditbook.memo.presets[0]", TOO_LONG, "creditbook.memo.charge-presets[0]", "현금")),
				Arguments.of("충전 문구 항목 길이 초과",
						Map.of("creditbook.memo.presets[0]", "음료", "creditbook.memo.charge-presets[0]", TOO_LONG)));
	}

	private static MemoPresetProperties bind(Map<String, String> source) {
		Binder binder = new Binder(new MapConfigurationPropertySource(source));
		ValidationBindHandler handler = new ValidationBindHandler(new SpringValidatorAdapter(validatorFactory.getValidator()));
		return binder.bindOrCreate("creditbook.memo", Bindable.of(MemoPresetProperties.class), handler);
	}

}

package com.creditbook.prepaid.application;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import com.creditbook.prepaid.domain.LedgerEntry;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/**
 * 메모 문구 칩 설정 ({@code creditbook.memo.*}, REQ-31). 매장마다 달라 코드에 두지 않는다.
 * 화면에 보여 줄 값일 뿐 서버 로직이 이 값으로 갈라지지 않는다 — 메모는 칩 밖의 자유 입력도 받는다.
 * <p>
 * 목록이 없거나 비었거나, 항목이 공백이거나 메모 최대 길이({@link LedgerEntry#MEMO_MAX_LENGTH})를 넘으면 기동을 멈춘다.
 * 칩을 눌러 넣은 메모가 서버에서 길이 초과로 거절되는 일이 없게 하기 위해서다.
 *
 * @param presets 사용(USE) 메모 문구
 * @param chargePresets 충전(CHARGE) 메모 문구
 */
@Validated
@ConfigurationProperties(prefix = "creditbook.memo")
public record MemoPresetProperties(
		@NotEmpty List<@NotBlank @Size(max = LedgerEntry.MEMO_MAX_LENGTH) String> presets,
		@NotEmpty List<@NotBlank @Size(max = LedgerEntry.MEMO_MAX_LENGTH) String> chargePresets) {

	public MemoPresetProperties {
		presets = presets == null ? null : List.copyOf(presets);
		chargePresets = chargePresets == null ? null : List.copyOf(chargePresets);
	}

}

package com.creditbook.prepaid.controller.dto;

import java.math.BigDecimal;
import java.util.List;

import com.creditbook.prepaid.application.MemoPresetProperties;
import com.creditbook.prepaid.domain.ChargePolicy;
import com.creditbook.prepaid.domain.LedgerEntry;

/**
 * 충전·사용 화면 입력 설정 (REQ-6, REQ-31). 화면은 이 값으로 입력을 미리 검사하고 문구 칩을 그릴 뿐, 최종 검증은 서버가 한다.
 *
 * @param chargeMaxAmount 1회 충전 한도 (원 단위 정수, 잔액 상한 아님)
 * @param chargeMemoPresets 충전 메모 문구
 * @param useMemoPresets 사용 메모 문구
 * @param memoMaxLength 메모·취소 사유 최대 길이
 */
public record LedgerInputSettingsResponse(
		BigDecimal chargeMaxAmount,
		List<String> chargeMemoPresets,
		List<String> useMemoPresets,
		int memoMaxLength) {

	public static LedgerInputSettingsResponse of(ChargePolicy chargePolicy, MemoPresetProperties memoPresets) {
		// 한도는 정수임이 ChargePolicy 에서 보장된다. 설정이 3E+5 처럼 적혀도 JSON 에 지수 표기가 나가지 않게 scale 0 으로 맞춘다
		return new LedgerInputSettingsResponse(chargePolicy.maxAmount().setScale(0), memoPresets.chargePresets(),
				memoPresets.presets(), LedgerEntry.MEMO_MAX_LENGTH);
	}

}

package com.creditbook.prepaid.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.creditbook.prepaid.application.MemoPresetProperties;
import com.creditbook.prepaid.controller.dto.LedgerInputSettingsResponse;
import com.creditbook.prepaid.domain.ChargePolicy;

/**
 * 충전·사용 화면 입력 설정 조회 (REQ-6, REQ-31). 로그인한 직원(STAFF·ADMIN)이면 누구나 부를 수 있다.
 * <p>
 * 값은 기동 때 읽은 설정 그대로다 — 한도는 {@link ChargePolicy} 빈에서, 문구는 {@link MemoPresetProperties} 에서 가져온다
 * (설정을 두 번 읽지 않는다). DB 를 읽지 않으므로 서비스·트랜잭션을 거치지 않는다.
 */
@RestController
public class LedgerInputSettingsController {

	private final ChargePolicy chargePolicy;
	private final MemoPresetProperties memoPresets;

	public LedgerInputSettingsController(ChargePolicy chargePolicy, MemoPresetProperties memoPresets) {
		this.chargePolicy = chargePolicy;
		this.memoPresets = memoPresets;
	}

	@GetMapping("/api/settings/ledger-input")
	public LedgerInputSettingsResponse getLedgerInputSettings() {
		return LedgerInputSettingsResponse.of(chargePolicy, memoPresets);
	}

}

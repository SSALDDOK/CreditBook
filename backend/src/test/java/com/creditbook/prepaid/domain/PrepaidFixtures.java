package com.creditbook.prepaid.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 도메인 단위 테스트 공용 준비물. 스프링 컨텍스트 없이 순수 객체만 만든다.
 * 잔액이 있는 계좌도 공개 API(open → charge)로만 만든다 — 잔액을 직접 넣는 뒷문은 두지 않는다.
 */
final class PrepaidFixtures {

	static final UUID EMPLOYEE_ID = UUID.fromString("00000000-0000-0000-0000-00000000e001");
	static final Instant NOW = Instant.parse("2026-09-30T01:00:00Z");
	/** application.yml 의 creditbook.charge.max-amount 와 같은 1회 충전 한도. */
	static final ChargePolicy POLICY = ChargePolicy.ofMaxAmount(300_000);
	/** 준비용 — 한도에 막히지 않고 원하는 잔액을 한 번에 만든다. */
	private static final ChargePolicy SETUP_POLICY = new ChargePolicy(PrepaidAccount.NUMERIC_12_MAX);

	private PrepaidFixtures() {
	}

	static BigDecimal won(long amount) {
		return BigDecimal.valueOf(amount);
	}

	static PrepaidAccount newAccount() {
		return PrepaidAccount.open(UUID.randomUUID(), NOW);
	}

	/** 잔액이 {@code balance} 원인 계좌. 0 이면 충전하지 않는다. */
	static PrepaidAccount accountWithBalance(long balance) {
		PrepaidAccount account = newAccount();
		if (balance > 0) {
			account.charge(won(balance), SETUP_POLICY, EMPLOYEE_ID, NOW, null);
		}
		return account;
	}

}

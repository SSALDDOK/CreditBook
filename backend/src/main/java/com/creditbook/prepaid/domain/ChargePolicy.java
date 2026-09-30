package com.creditbook.prepaid.domain;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 충전 정책 값 (REQ-6). {@code maxAmount} 는 <b>1회</b> 충전 한도이며 잔액 상한이 아니다.
 * <p>
 * 도메인은 설정을 직접 읽지 않는다. 서비스가 {@code creditbook.charge.max-amount} 로 만들어 넘긴다 —
 * 경계값 테스트가 상한을 바꿔 가며 돌 수 있게 하기 위해서다.
 */
public record ChargePolicy(BigDecimal maxAmount) {

	public ChargePolicy {
		Objects.requireNonNull(maxAmount, "maxAmount");
		if (maxAmount.signum() <= 0 || maxAmount.stripTrailingZeros().scale() > 0) {
			throw new IllegalArgumentException("1회 충전 한도는 1원 이상의 정수여야 한다: " + maxAmount);
		}
	}

	public static ChargePolicy ofMaxAmount(long maxAmount) {
		return new ChargePolicy(BigDecimal.valueOf(maxAmount));
	}

	/** 이 금액이 1회 충전 한도 안인가 (한도와 같으면 허용). */
	public boolean isWithinLimit(BigDecimal amount) {
		return amount.compareTo(maxAmount) <= 0;
	}

}

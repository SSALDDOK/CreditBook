package com.creditbook.prepaid.domain;

/**
 * 취소할 수 없는 거래를 취소하려 할 때 — 다른 계좌의 거래, 유형이 맞지 않는 거래(예: 사용 취소 대상이 CHARGE),
 * 이미 반제 행인 거래(반제 행은 다시 반제할 수 없다).
 */
public class InvalidCancelTargetException extends PrepaidDomainException {

	InvalidCancelTargetException(String message) {
		super(message);
	}

}

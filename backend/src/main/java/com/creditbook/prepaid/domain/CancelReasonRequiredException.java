package com.creditbook.prepaid.domain;

/**
 * 취소 사유 없이 취소하려 할 때. 취소는 사유가 필수다 (REQ-7, REQ-34).
 */
public class CancelReasonRequiredException extends PrepaidDomainException {

	CancelReasonRequiredException() {
		super("취소 사유를 입력해 주세요.");
	}

}

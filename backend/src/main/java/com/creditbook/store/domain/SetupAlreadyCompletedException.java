package com.creditbook.store.domain;

/**
 * 이미 개설된 매장에 다시 개설 요청 (REQ-29). 직원 계정이 하나라도 있을 때. 오류 코드 SETUP_ALREADY_COMPLETED 와 짝이다.
 */
public class SetupAlreadyCompletedException extends StoreDomainException {

	public SetupAlreadyCompletedException() {
		super("이미 개설된 매장입니다. 로그인해 주세요.");
	}

}

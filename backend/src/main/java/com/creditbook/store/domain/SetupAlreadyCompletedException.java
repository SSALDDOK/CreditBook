package com.creditbook.store.domain;

/**
 * 이미 개설된 매장에 다시 개설 요청 (REQ-29). 직원 계정이 하나라도 있거나, 동시에 들어온 다른 개설 요청이 매장 행을 먼저 만들어
 * ux_store_profile_singleton 에 걸렸을 때. 오류 코드 SETUP_ALREADY_COMPLETED 와 짝이다.
 */
public class SetupAlreadyCompletedException extends StoreDomainException {

	public SetupAlreadyCompletedException() {
		super("이미 개설된 매장입니다. 로그인해 주세요.");
	}

	public SetupAlreadyCompletedException(Throwable cause) {
		this();
		initCause(cause);
	}

}

package com.creditbook.store.domain;

/**
 * 초대 코드로 매장을 열 수 없음 (REQ-36). 없는 코드·사용 중지된 코드·한도를 다 쓴 코드를 구분하지 않는다 — 어느 코드가 있는지
 * 알아내는 데 쓰이지 않게 한다. 오류 코드 INVALID_INVITE_CODE 와 짝이다.
 */
public class InvalidInviteCodeException extends StoreDomainException {

	public InvalidInviteCodeException() {
		super("초대 코드를 확인해 주세요.");
	}

}

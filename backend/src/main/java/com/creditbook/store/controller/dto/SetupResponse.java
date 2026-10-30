package com.creditbook.store.controller.dto;

import com.creditbook.store.application.StoreSetupResult;

/**
 * 매장 개설 결과 (REQ-29). 비밀번호·해시·직원 ID 는 싣지 않는다.
 *
 * @param storeName 저장된 매장명
 * @param loginId 만들어진 ADMIN 계정의 로그인 아이디
 * @param name 사장 이름
 * @param role 항상 "ADMIN"
 */
public record SetupResponse(String storeName, String loginId, String name, String role) {

	public static SetupResponse from(StoreSetupResult result) {
		return new SetupResponse(result.storeName(), result.loginId(), result.name(), result.role().name());
	}

}

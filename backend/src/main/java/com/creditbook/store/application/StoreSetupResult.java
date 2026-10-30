package com.creditbook.store.application;

import java.util.UUID;

import com.creditbook.auth.domain.Role;

/**
 * 매장 개설 결과. 비밀번호·해시는 담지 않는다.
 *
 * @param storeName 저장된 매장명 (앞뒤 공백 제거)
 * @param employeeId 만들어진 ADMIN 직원 ID — 로그용, 응답에는 싣지 않는다
 * @param loginId 로그인 아이디
 * @param name 저장된 사장 이름 (앞뒤 공백 제거)
 * @param role 항상 ADMIN
 */
public record StoreSetupResult(String storeName, UUID employeeId, String loginId, String name, Role role) {
}

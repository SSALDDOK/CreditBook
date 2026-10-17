package com.creditbook.customer.domain;

import java.util.UUID;

/**
 * 연락처가 같은 기존 고객 (REQ-1 중복 연락처 확인). 직원이 이름으로 같은 사람인지 판단하도록 이름·활성 여부만 담는다.
 * 연락처는 담지 않는다 — 요청한 직원이 이미 아는 번호이고, 응답·로그에 다시 내보낼 이유가 없다.
 */
public record SamePhoneCustomer(UUID id, String name, boolean active) {
}

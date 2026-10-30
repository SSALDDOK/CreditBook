package com.creditbook.store.controller.dto;

/**
 * 매장 개설 가능 여부 (REQ-29). 로그인 전에도 부르므로 매장명·계정 수 같은 다른 정보는 싣지 않는다.
 *
 * @param available 직원 계정이 하나도 없을 때만 true
 */
public record SetupStatusResponse(boolean available) {
}

package com.creditbook.customer.application;

/**
 * 고객 정보 수정 요청 (REQ-3). null 인 칸은 그대로 둔다. 입력 형식(하이픈 지우기 등)은 controller 가 정리해 넘긴다.
 *
 * @param expectedVersion 화면이 고객 정보를 읽을 때 받은 버전
 * @param name 새 이름 (null 이면 그대로)
 * @param phone 새 연락처, 숫자 정규형 (null 이면 그대로)
 * @param memo 새 메모 (null 이면 그대로, 비었거나 공백뿐이면 지우기)
 * @param confirmDuplicatePhone 같은 번호 고객이 있어도 연락처를 바꿀지 여부
 */
public record CustomerEditCommand(
		long expectedVersion,
		String name,
		String phone,
		String memo,
		boolean confirmDuplicatePhone) {
}

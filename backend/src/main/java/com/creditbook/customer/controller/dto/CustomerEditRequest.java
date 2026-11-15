package com.creditbook.customer.controller.dto;

import com.creditbook.customer.application.CustomerEditCommand;
import com.creditbook.customer.domain.Customer;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * 고객 정보 수정 요청 (PATCH /api/customers/{id}, REQ-3). 보낸 칸만 고치고, 없거나 null 인 칸은 그대로 둔다.
 * <ul>
 * <li>{@code version} — 필수. 상세 조회 응답의 version 을 그대로 보낸다. 지금 버전과 다르면 409 CONCURRENT_MODIFICATION</li>
 * <li>{@code name} — 공백뿐이면 400. 앞뒤 공백은 지워 저장한다</li>
 * <li>{@code phone} — 빈 문자열을 포함해 형식이 틀리면 400 (연락처는 지울 수 없다). 하이픈을 지워 숫자만 저장한다</li>
 * <li>{@code memo} — 빈 문자열·공백뿐이면 메모를 지운다. 200자(코드 포인트) 제한은 도메인이 검사한다 (등록과 같은 이유로 @Size 를 쓰지 않는다)</li>
 * </ul>
 * 받지 않는 항목(balance·active 등)은 본문을 읽을 때 400 으로 거절된다 (REQ-27).
 *
 * @param confirmDuplicatePhone 같은 번호 고객이 있어도 연락처를 바꿀지 (선택 — 누락·null 이면 false)
 */
public record CustomerEditRequest(
		@NotNull(message = "버전 정보가 없습니다. 고객 정보를 다시 불러와 주세요.")
		@PositiveOrZero(message = "버전 정보가 올바르지 않습니다. 고객 정보를 다시 불러와 주세요.")
		Long version,

		// null 은 통과(그대로 둠), 공백 아닌 글자가 하나도 없으면 거절
		@Pattern(regexp = "(?s).*\\S.*", message = "이름을 입력해 주세요.")
		@Size(max = Customer.NAME_MAX_LENGTH, message = "이름은 {max}자 이하여야 합니다.")
		String name,

		@Pattern(regexp = PHONE_INPUT_PATTERN, message = "연락처는 숫자 9–11자리여야 합니다. 하이픈(-)은 숫자 사이에만 쓸 수 있습니다.")
		String phone,

		String memo,

		Boolean confirmDuplicatePhone) {

	/** 등록({@link CustomerRegisterRequest#PHONE_INPUT_PATTERN})에서 빈 문자열 허용({@code ^$|})만 뺀 규칙 — 연락처는 지울 수 없다. */
	static final String PHONE_INPUT_PATTERN = "^(?:[0-9]-?){8,10}[0-9]$";

	/** 서비스에 넘길 명령. 연락처는 하이픈을 지운 숫자만 남긴다. version 은 검증을 거친 뒤에만 부른다. */
	public CustomerEditCommand toCommand() {
		return new CustomerEditCommand(version, name, phone == null ? null : phone.replace("-", ""), memo,
				Boolean.TRUE.equals(confirmDuplicatePhone));
	}

}

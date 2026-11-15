package com.creditbook.customer.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 고객 정보 수정(REQ-3)에서 실제로 바뀐 칸. 값은 담지 않고 어느 칸이 바뀌었는지만 담는다 —
 * 변경 기록(customer_change_logs)과 로그에 이름·연락처 값을 남기지 않기 위해서다.
 *
 * @param nameChanged 이름이 바뀌었으면 true (앞뒤 공백만 다른 것은 바뀐 것이 아니다)
 * @param phoneChanged 연락처(숫자 정규형)가 바뀌었으면 true
 * @param memoChanged 메모가 바뀌었으면 true (지우기 포함)
 */
public record CustomerChanges(boolean nameChanged, boolean phoneChanged, boolean memoChanged) {

	/** 바뀐 칸이 없음. */
	public static final CustomerChanges NONE = new CustomerChanges(false, false, false);

	/** 바뀐 칸이 하나라도 있으면 true. */
	public boolean hasAny() {
		return nameChanged || phoneChanged || memoChanged;
	}

	/** 바뀐 칸의 이름 (로그용, 순서 name → phone → memo). */
	public List<String> changedFieldNames() {
		List<String> names = new ArrayList<>(3);
		if (nameChanged) {
			names.add("name");
		}
		if (phoneChanged) {
			names.add("phone");
		}
		if (memoChanged) {
			names.add("memo");
		}
		return List.copyOf(names);
	}

}

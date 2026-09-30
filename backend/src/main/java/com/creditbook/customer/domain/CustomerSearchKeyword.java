package com.creditbook.customer.domain;

import java.util.Objects;

/**
 * 고객 목록 검색어 (REQ-2). 검색창 하나에 입력한 값을 이름 검색인지 연락처 뒷자리 검색인지 해석한다.
 * <p>
 * 해석 규칙:
 * <ol>
 * <li>null·공백뿐 → {@link Type#NONE} (전체 목록)</li>
 * <li>하이픈·공백을 지운 뒤 숫자만 남으면 → {@link Type#PHONE_SUFFIX} (저장된 연락처가 그 숫자로 끝나는 고객).
 * 값은 숫자만 남긴 형태 — 저장 형식(숫자 정규형)과 같게 맞춘다</li>
 * <li>그 밖 → {@link Type#NAME} (이름에 앞뒤 공백을 지운 검색어가 들어 있는 고객)</li>
 * </ol>
 * 이름은 숫자만으로 이루어질 수 없다고 본다 — 숫자만 입력하면 항상 연락처로 해석한다.
 * 검색어는 개인정보(이름·연락처)일 수 있으므로 {@link #toString()} 에 값을 넣지 않는다 (로그 노출 방지).
 */
public final class CustomerSearchKeyword {

	public enum Type {
		NONE, NAME, PHONE_SUFFIX
	}

	private static final CustomerSearchKeyword NONE = new CustomerSearchKeyword(Type.NONE, "");

	private final Type type;
	private final String value;

	private CustomerSearchKeyword(Type type, String value) {
		this.type = type;
		this.value = value;
	}

	public static CustomerSearchKeyword parse(String query) {
		if (query == null || query.isBlank()) {
			return NONE;
		}
		String stripped = query.strip();
		String withoutSeparators = stripped.replaceAll("[\\s-]", "");
		if (!withoutSeparators.isEmpty() && withoutSeparators.chars().allMatch(c -> c >= '0' && c <= '9')) {
			return new CustomerSearchKeyword(Type.PHONE_SUFFIX, withoutSeparators);
		}
		return new CustomerSearchKeyword(Type.NAME, stripped);
	}

	public Type type() {
		return type;
	}

	/** 검색할 값. NONE 이면 빈 문자열. */
	public String value() {
		return value;
	}

	public boolean isEmpty() {
		return type == Type.NONE;
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof CustomerSearchKeyword other && type == other.type && value.equals(other.value);
	}

	@Override
	public int hashCode() {
		return Objects.hash(type, value);
	}

	@Override
	public String toString() {
		return "CustomerSearchKeyword[type=" + type + ", length=" + value.length() + "]";
	}

}

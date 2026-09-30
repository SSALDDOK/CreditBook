package com.creditbook.customer.domain;

/**
 * 연락처 마스킹 (REQ-26 설계 원칙). 응답에 나가는 연락처는 권한과 무관하게 항상 이 함수를 거친다.
 * <p>
 * 규칙: 앞자리(지역·통신사 번호)와 뒤 4자리만 보이고 가운데 자리는 자릿수만큼 {@code *} 로 가린다.
 * <ul>
 * <li>앞자리는 {@code 02}(서울)로 시작하면 2자리, 그 밖에는 3자리</li>
 * <li>11자리 {@code 01012345678} → {@code 010-****-5678}</li>
 * <li>10자리 {@code 0212345678} → {@code 02-****-5678}, {@code 0311234567} → {@code 031-***-4567}</li>
 * <li>9자리 {@code 021234567} → {@code 02-***-4567}, {@code 031234567} → {@code 031-**-4567}</li>
 * </ul>
 * 저장 형식(숫자 9–11자리)이 아닌 값은 형식을 추정하지 않고 모든 글자를 {@code *} 로 가린다. null 은 null, 빈 값은 빈 값.
 * 어떤 입력에도 예외를 던지지 않는다 — 마스킹 실패로 목록 전체가 500 이 되면 안 된다.
 */
public final class PhoneNumberMasker {

	private static final int VISIBLE_SUFFIX_LENGTH = 4;
	private static final int MIN_DIGITS = 9;
	private static final int MAX_DIGITS = 11;
	private static final String SEOUL_AREA_CODE = "02";

	private PhoneNumberMasker() {
	}

	public static String mask(String phone) {
		if (phone == null || phone.isEmpty()) {
			return phone;
		}
		if (!isStoredFormat(phone)) {
			return "*".repeat(phone.length());
		}
		int prefixLength = phone.startsWith(SEOUL_AREA_CODE) ? 2 : 3;
		int suffixStart = phone.length() - VISIBLE_SUFFIX_LENGTH;
		return phone.substring(0, prefixLength)
				+ "-" + "*".repeat(suffixStart - prefixLength)
				+ "-" + phone.substring(suffixStart);
	}

	private static boolean isStoredFormat(String phone) {
		if (phone.length() < MIN_DIGITS || phone.length() > MAX_DIGITS) {
			return false;
		}
		for (int i = 0; i < phone.length(); i++) {
			char c = phone.charAt(i);
			if (c < '0' || c > '9') {
				return false;
			}
		}
		return true;
	}

}

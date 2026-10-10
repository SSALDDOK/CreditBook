package com.creditbook.prepaid.domain;

import java.util.regex.Pattern;

/**
 * 요청 키 (Idempotency-Key, REQ-10). 같은 키로 다시 온 요청은 새 거래를 만들지 않고 처음 만든 거래로 응답한다.
 * <p>
 * 형식은 영문·숫자·하이픈 1–64자다 (ledger_entries.idempotency_key VARCHAR(64)). 키는 전역에서 유일하고
 * (ux_ledger_entries_idem) 만료되지 않는다 — 원장이 append-only 라 한 번 쓴 키는 계속 그 거래를 가리킨다.
 *
 * @param value 키 값 (대소문자 구분)
 */
public record IdempotencyKey(String value) {

	/** ledger_entries.idempotency_key VARCHAR(64) */
	public static final int MAX_LENGTH = 64;

	private static final Pattern FORMAT = Pattern.compile("^[A-Za-z0-9-]{1," + MAX_LENGTH + "}$");

	/** 로그에 남기는 앞부분 길이. 키 전체는 로그에 남기지 않는다. */
	private static final int LOG_PREFIX_LENGTH = 8;

	/**
	 * @throws InvalidIdempotencyKeyException 값이 없거나 형식에 맞지 않을 때 (거절된 값은 예외에 담지 않는다)
	 */
	public IdempotencyKey {
		if (value == null || !FORMAT.matcher(value).matches()) {
			throw new InvalidIdempotencyKeyException();
		}
	}

	/** 헤더 값에서 키를 만든다. 헤더가 없으면 null 이 들어온다. */
	public static IdempotencyKey of(String value) {
		return new IdempotencyKey(value);
	}

	/** 로그용 앞부분 (최대 8자). 같은 요청의 로그를 이어 볼 수 있을 만큼만 남긴다. */
	public String logPrefix() {
		return value.substring(0, Math.min(LOG_PREFIX_LENGTH, value.length()));
	}

}

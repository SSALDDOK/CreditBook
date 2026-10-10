package com.creditbook.prepaid.controller;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * 요청 키(REQ-10) 헤더 이름과 거래 생성 응답(201)을 만드는 공용 처리. 충전·사용(이후 사용 취소) 컨트롤러가 같이 쓴다.
 */
public final class IdempotencyHeaders {

	/** 요청 헤더 — 필수, 영문·숫자·하이픈 1–64자. */
	public static final String IDEMPOTENCY_KEY = "Idempotency-Key";

	/** 응답 헤더 — 같은 요청 키로 다시 온 요청에 처음 거래로 재응답할 때만 {@code true} 로 싣는다. */
	public static final String IDEMPOTENT_REPLAYED = "Idempotent-Replayed";

	private IdempotencyHeaders() {
	}

	/** 201 + Location(/api/ledger-entries/{id}). 재응답이면 Idempotent-Replayed: true 를 더한다. */
	static <T> ResponseEntity<T> created(UUID entryId, boolean replayed, T body) {
		URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
				.path("/api/ledger-entries/{id}")
				.buildAndExpand(entryId)
				.toUri();
		ResponseEntity.BodyBuilder builder = ResponseEntity.created(location);
		if (replayed) {
			builder.header(IDEMPOTENT_REPLAYED, "true");
		}
		return builder.body(body);
	}

}

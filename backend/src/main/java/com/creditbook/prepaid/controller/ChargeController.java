package com.creditbook.prepaid.controller;

import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.creditbook.global.security.CurrentEmployee;
import com.creditbook.global.security.UnauthenticatedException;
import com.creditbook.prepaid.application.ChargeResult;
import com.creditbook.prepaid.application.ChargeService;
import com.creditbook.prepaid.controller.dto.ChargeRequest;
import com.creditbook.prepaid.controller.dto.ChargeResponse;
import com.creditbook.prepaid.domain.IdempotencyKey;

import jakarta.validation.Valid;

/**
 * 충전 API (REQ-5). 요청 형식 검증과 DTO 변환만 하고, 금액 규칙과 잔액 계산은 서비스·도메인이 판단한다.
 * <p>
 * 처리 직원은 {@link CurrentEmployee} 에서만 꺼낸다 (요청 본문·헤더로 받지 않는다). 구현 빈이 없거나 직원을 알 수 없으면
 * 거래를 만들지 않고 401 로 거절한다 — 빈이 없어도 애플리케이션은 기동한다.
 * <p>
 * 요청 키(Idempotency-Key, REQ-10)는 필수다. 없거나 형식이 틀리면 400 INVALID_IDEMPOTENCY_KEY. 같은 키로 같은 요청이 다시 오면
 * 처음 거래를 201 로 다시 돌려주고 응답 헤더 Idempotent-Replayed: true 를 싣는다.
 */
@RestController
public class ChargeController {

	private final ChargeService chargeService;
	private final ObjectProvider<CurrentEmployee> currentEmployee;

	public ChargeController(ChargeService chargeService, ObjectProvider<CurrentEmployee> currentEmployee) {
		this.chargeService = chargeService;
		this.currentEmployee = currentEmployee;
	}

	/** 고객의 선결제 계좌에 충전한다. 만들어진 CHARGE 거래를 201 로 돌려준다. */
	@PostMapping("/api/customers/{customerId}/charges")
	public ResponseEntity<ChargeResponse> charge(@PathVariable("customerId") UUID customerId,
			@Valid @RequestBody ChargeRequest request,
			@RequestHeader(name = IdempotencyHeaders.IDEMPOTENCY_KEY, required = false) String idempotencyKey) {
		UUID performedBy = requireCurrentEmployeeId();
		IdempotencyKey key = IdempotencyKey.of(idempotencyKey);
		ChargeResult result = chargeService.charge(customerId, request.amount(), request.memo(), performedBy, key);
		return IdempotencyHeaders.created(result.entryId(), result.replayed(), ChargeResponse.from(result));
	}

	private UUID requireCurrentEmployeeId() {
		CurrentEmployee employee = currentEmployee.getIfAvailable();
		UUID id = employee == null ? null : employee.id();
		if (id == null) {
			throw new UnauthenticatedException();
		}
		return id;
	}

}

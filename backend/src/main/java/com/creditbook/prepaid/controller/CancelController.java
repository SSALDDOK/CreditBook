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
import com.creditbook.prepaid.application.CancelService;
import com.creditbook.prepaid.application.UseCancelResult;
import com.creditbook.prepaid.controller.dto.UseCancelRequest;
import com.creditbook.prepaid.controller.dto.UseCancelResponse;
import com.creditbook.prepaid.domain.IdempotencyKey;

import jakarta.validation.Valid;

/**
 * 취소 API — 사용 취소 (REQ-34). 요청 형식 검증과 DTO 변환만 하고, 취소 대상 판단·잔액 계산은 서비스·도메인이 한다.
 * <p>
 * 처리 직원은 {@link CurrentEmployee} 에서만 꺼낸다 (요청 본문·헤더로 받지 않는다). 구현 빈이 없거나 직원을 알 수 없으면
 * 거래를 만들지 않고 401 로 거절한다.
 * <p>
 * 요청 키(Idempotency-Key, REQ-10)는 충전·사용과 같은 규칙으로 필수다. 같은 키로 같은 요청이 다시 오면
 * 처음 거래를 201 로 다시 돌려주고 응답 헤더 Idempotent-Replayed: true 를 싣는다.
 */
@RestController
public class CancelController {

	private final CancelService cancelService;
	private final ObjectProvider<CurrentEmployee> currentEmployee;

	public CancelController(CancelService cancelService, ObjectProvider<CurrentEmployee> currentEmployee) {
		this.cancelService = cancelService;
		this.currentEmployee = currentEmployee;
	}

	/** 고객의 사용 건을 전액 취소한다. 만들어진 USE_CANCEL 거래를 201 로 돌려준다. */
	@PostMapping("/api/customers/{customerId}/uses/{useEntryId}/cancel")
	public ResponseEntity<UseCancelResponse> cancelUse(@PathVariable("customerId") UUID customerId,
			@PathVariable("useEntryId") UUID useEntryId,
			@Valid @RequestBody UseCancelRequest request,
			@RequestHeader(name = IdempotencyHeaders.IDEMPOTENCY_KEY, required = false) String idempotencyKey) {
		UUID performedBy = requireCurrentEmployeeId();
		IdempotencyKey key = IdempotencyKey.of(idempotencyKey);
		UseCancelResult result = cancelService.cancelUse(customerId, useEntryId, request.reason(), performedBy, key);
		return IdempotencyHeaders.created(result.entryId(), result.replayed(), UseCancelResponse.from(result));
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

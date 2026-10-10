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
import com.creditbook.prepaid.application.UsageResult;
import com.creditbook.prepaid.application.UsageService;
import com.creditbook.prepaid.controller.dto.UsageRequest;
import com.creditbook.prepaid.controller.dto.UsageResponse;
import com.creditbook.prepaid.domain.IdempotencyKey;

import jakarta.validation.Valid;

/**
 * 사용 API (REQ-8, REQ-9). 요청 형식 검증과 DTO 변환만 하고, 금액 규칙·잔액 부족 판단·잔액 계산은 서비스·도메인이 한다.
 * <p>
 * 처리 직원은 {@link CurrentEmployee} 에서만 꺼낸다 (요청 본문·헤더로 받지 않는다). 구현 빈이 없거나 직원을 알 수 없으면
 * 거래를 만들지 않고 401 로 거절한다 — 빈이 없어도 애플리케이션은 기동한다.
 * <p>
 * 요청 키(Idempotency-Key, REQ-10)는 필수다. 없거나 형식이 틀리면 400 INVALID_IDEMPOTENCY_KEY. 같은 키로 같은 요청이 다시 오면
 * 처음 거래를 201 로 다시 돌려주고 응답 헤더 Idempotent-Replayed: true 를 싣는다.
 */
@RestController
public class UsageController {

	private final UsageService usageService;
	private final ObjectProvider<CurrentEmployee> currentEmployee;

	public UsageController(UsageService usageService, ObjectProvider<CurrentEmployee> currentEmployee) {
		this.usageService = usageService;
		this.currentEmployee = currentEmployee;
	}

	/** 고객의 선결제 잔액에서 사용 금액을 뺀다. 만들어진 USE 거래를 201 로 돌려준다. */
	@PostMapping("/api/customers/{customerId}/uses")
	public ResponseEntity<UsageResponse> use(@PathVariable("customerId") UUID customerId,
			@Valid @RequestBody UsageRequest request,
			@RequestHeader(name = IdempotencyHeaders.IDEMPOTENCY_KEY, required = false) String idempotencyKey) {
		UUID performedBy = requireCurrentEmployeeId();
		IdempotencyKey key = IdempotencyKey.of(idempotencyKey);
		UsageResult result = usageService.use(customerId, request.amount(), request.memo(), performedBy, key);
		return IdempotencyHeaders.created(result.entryId(), result.replayed(), UsageResponse.from(result));
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

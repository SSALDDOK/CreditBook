package com.creditbook.customer.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 고객 등록 결과. 컨트롤러가 엔티티를 직접 다루지 않도록 서비스가 필요한 값만 담아 돌려준다.
 *
 * @param memo 정규화된 메모 (없으면 null)
 */
public record RegisteredCustomer(
		UUID customerId,
		String name,
		String phone,
		UUID accountId,
		BigDecimal balance,
		String memo,
		Instant createdAt) {
}

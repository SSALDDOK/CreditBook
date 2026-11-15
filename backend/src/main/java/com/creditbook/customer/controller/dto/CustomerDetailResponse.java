package com.creditbook.customer.controller.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.creditbook.customer.domain.CustomerDetail;
import com.creditbook.customer.domain.PhoneNumberMasker;

/**
 * 고객 상세 응답 (GET /api/customers/{id}, REQ-11). 화면은 거래 뒤 이 응답을 다시 받아 서버 잔액으로 표시를 확정한다.
 * <p>
 * 연락처 원문은 나가지 않는다 (REQ-26). 원문을 받는 생성자 대신 팩토리 메서드만 쓰고, 팩토리가 {@link PhoneNumberMasker} 로 가린 값을 넣는다.
 * 고객 정보 수정(PATCH /api/customers/{id}, REQ-3)의 성공 응답도 이 모양이다. 화면은 {@code version} 을 수정 요청에 그대로 돌려보낸다.
 *
 * @param maskedPhone 가운데 자리를 가린 연락처 (예: {@code 010-****-5678})
 * @param balance 선결제 잔액 (원 단위 정수). recentEntries[0].balanceAfter 와 같은 시점의 값
 * @param active 활성 고객이면 true. 비활성 고객도 조회된다
 * @param memo 고객 메모 (없으면 null)
 * @param version 고객 버전 — 수정 요청의 {@code version} 으로 돌려보낸다
 * @param recentEntries 최근 거래 (최신순, 최대 5건). 거래가 없으면 빈 배열
 */
public record CustomerDetailResponse(
		UUID id,
		String name,
		String maskedPhone,
		BigDecimal balance,
		boolean active,
		Instant createdAt,
		String memo,
		long version,
		List<RecentLedgerEntryResponse> recentEntries) {

	public static CustomerDetailResponse from(CustomerDetail detail) {
		return new CustomerDetailResponse(detail.id(), detail.name(), PhoneNumberMasker.mask(detail.phone()),
				detail.balance(), detail.active(), detail.createdAt(), detail.memo(), detail.version(),
				detail.recentEntries().stream().map(RecentLedgerEntryResponse::from).toList());
	}

}

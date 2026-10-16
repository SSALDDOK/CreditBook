package com.creditbook.prepaid.controller.dto;

import java.util.List;

import org.springframework.data.domain.Page;

import com.creditbook.prepaid.application.LedgerHistoryItem;

/**
 * 거래 이력 응답 (GET /api/customers/{customerId}/transactions). Spring Data 의 Page 를 그대로 직렬화하지 않고
 * 필요한 페이지 정보만 담는다 (고객 목록 응답과 같은 형태).
 *
 * @param content 이 페이지의 거래 (최신순)
 * @param page 0부터 시작하는 페이지 번호
 * @param size 요청한 페이지 크기
 * @param totalElements 고객의 전체 거래 수 (취소 행 포함)
 * @param totalPages 전체 페이지 수
 * @param hasNext 다음 페이지가 있는지
 */
public record LedgerEntryListResponse(
		List<LedgerEntryListItemResponse> content,
		int page,
		int size,
		long totalElements,
		int totalPages,
		boolean hasNext) {

	public static LedgerEntryListResponse from(Page<LedgerHistoryItem> page) {
		return new LedgerEntryListResponse(
				page.getContent().stream().map(LedgerEntryListItemResponse::from).toList(),
				page.getNumber(),
				page.getSize(),
				page.getTotalElements(),
				page.getTotalPages(),
				page.hasNext());
	}

}

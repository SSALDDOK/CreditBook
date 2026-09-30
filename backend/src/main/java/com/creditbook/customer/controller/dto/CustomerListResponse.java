package com.creditbook.customer.controller.dto;

import java.util.List;

import org.springframework.data.domain.Page;

import com.creditbook.customer.domain.CustomerSummary;

/**
 * 고객 목록 응답 (GET /api/customers). Spring Data 의 Page 를 그대로 직렬화하지 않고 필요한 페이지 정보만 담는다
 * (Page 의 JSON 형태는 라이브러리 버전에 따라 바뀔 수 있어 API 계약으로 쓰지 않는다).
 * 각 고객의 연락처는 {@link CustomerResponse} 가 가린다.
 *
 * @param content 이 페이지의 고객 (최근 등록순)
 * @param page 0부터 시작하는 페이지 번호
 * @param size 요청한 페이지 크기
 * @param totalElements 조건에 맞는 활성 고객 수
 * @param totalPages 전체 페이지 수
 * @param hasNext 다음 페이지가 있는지
 */
public record CustomerListResponse(
		List<CustomerResponse> content,
		int page,
		int size,
		long totalElements,
		int totalPages,
		boolean hasNext) {

	public static CustomerListResponse from(Page<CustomerSummary> page) {
		return new CustomerListResponse(
				page.getContent().stream().map(CustomerResponse::from).toList(),
				page.getNumber(),
				page.getSize(),
				page.getTotalElements(),
				page.getTotalPages(),
				page.hasNext());
	}

}

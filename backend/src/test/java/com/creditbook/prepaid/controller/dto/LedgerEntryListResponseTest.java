package com.creditbook.prepaid.controller.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import com.creditbook.prepaid.application.LedgerHistoryItem;
import com.creditbook.prepaid.domain.LedgerEntryType;
import com.creditbook.prepaid.domain.LedgerHistoryEntry;

/**
 * 거래 이력 응답 변환. 스프링 없이 DTO 팩토리만 본다 (JSON 형태는 컨트롤러 슬라이스 테스트에서 본다).
 */
class LedgerEntryListResponseTest {

	private static final UUID USE_ID = UUID.fromString("7a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d");
	private static final UUID CANCEL_ID = UUID.fromString("5e6f7a8b-9c0d-4e1f-8a2b-3c4d5e6f7a8b");
	private static final Instant PERFORMED_AT = Instant.parse("2026-10-16T05:22:00Z");

	private static final LedgerHistoryEntry USE = new LedgerHistoryEntry(USE_ID, LedgerEntryType.USE,
			new BigDecimal("4500"), new BigDecimal("-4500"), new BigDecimal("45500"), "음료, 베이커리", PERFORMED_AT,
			"김직원", true, null);
	private static final LedgerHistoryEntry USE_CANCEL = new LedgerHistoryEntry(CANCEL_ID, LedgerEntryType.USE_CANCEL,
			new BigDecimal("4500"), new BigDecimal("4500"), new BigDecimal("50000"), "주문 착오",
			PERFORMED_AT.plusSeconds(60), "김직원", false, USE_ID);

	@Test
	@DisplayName("거래 한 건은 저장된 값 그대로 옮기고 원거래 ID 와 취소 가능 여부를 함께 담는다")
	void item_carries_entry_values_reverses_id_and_cancellable() {
		// given
		LedgerHistoryItem item = new LedgerHistoryItem(USE_CANCEL, false);

		// when
		LedgerEntryListItemResponse response = LedgerEntryListItemResponse.from(item);

		// then
		assertThat(response.id()).isEqualTo(CANCEL_ID);
		assertThat(response.type()).isEqualTo(LedgerEntryType.USE_CANCEL);
		assertThat(response.amount()).isEqualByComparingTo("4500");
		assertThat(response.signedAmount()).isEqualByComparingTo("4500");
		assertThat(response.balanceAfter()).isEqualByComparingTo("50000");
		assertThat(response.memo()).isEqualTo("주문 착오");
		assertThat(response.performedAt()).isEqualTo(PERFORMED_AT.plusSeconds(60));
		assertThat(response.performedByName()).isEqualTo("김직원");
		assertThat(response.cancelled()).isFalse();
		assertThat(response.reversesId()).isEqualTo(USE_ID);
		assertThat(response.cancellable()).isFalse();
	}

	@Test
	@DisplayName("취소된 사용 건은 cancelled 가 true 이고 원거래가 아니므로 reversesId 는 null 이다")
	void cancelled_original_has_no_reverses_id() {
		// given
		LedgerHistoryItem item = new LedgerHistoryItem(USE, false);

		// when
		LedgerEntryListItemResponse response = LedgerEntryListItemResponse.from(item);

		// then
		assertThat(response.cancelled()).isTrue();
		assertThat(response.reversesId()).isNull();
		assertThat(response.signedAmount()).isEqualByComparingTo("-4500");
	}

	@Test
	@DisplayName("페이지 정보는 Spring Page 의 값 중 필요한 것만 옮긴다")
	void page_info_is_copied() {
		// given: 2페이지(0부터 1) — 전체 7건, 크기 5
		PageImpl<LedgerHistoryItem> page = new PageImpl<>(
				List.of(new LedgerHistoryItem(USE_CANCEL, false), new LedgerHistoryItem(USE, false)),
				PageRequest.of(1, 5), 7);

		// when
		LedgerEntryListResponse response = LedgerEntryListResponse.from(page);

		// then
		assertThat(response.content()).extracting(LedgerEntryListItemResponse::id).containsExactly(CANCEL_ID, USE_ID);
		assertThat(response.page()).isEqualTo(1);
		assertThat(response.size()).isEqualTo(5);
		assertThat(response.totalElements()).isEqualTo(7);
		assertThat(response.totalPages()).isEqualTo(2);
		assertThat(response.hasNext()).isFalse();
	}

}

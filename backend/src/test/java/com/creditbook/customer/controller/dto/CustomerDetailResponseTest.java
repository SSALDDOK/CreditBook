package com.creditbook.customer.controller.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.creditbook.customer.domain.CustomerDetail;
import com.creditbook.prepaid.domain.LedgerEntryType;

/**
 * 고객 상세 응답 변환 (REQ-11). 스프링 없이 DTO 팩토리만 본다. 연락처는 모두 가상 번호다.
 */
@Tag("REQ-11")
class CustomerDetailResponseTest {

	private static final UUID CUSTOMER_ID = UUID.fromString("0f9a3c1e-5b7d-4e2a-9c8b-1d2e3f4a5b6c");
	private static final Instant CREATED_AT = Instant.parse("2026-10-01T01:00:00Z");
	private static final Instant PERFORMED_AT = Instant.parse("2026-10-15T02:30:00Z");

	private static CustomerDetail.RecentEntry useEntry(boolean cancelled) {
		return new CustomerDetail.RecentEntry(UUID.fromString("7a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"),
				LedgerEntryType.USE, new BigDecimal("4500"), new BigDecimal("-4500"), new BigDecimal("5500"), "음료",
				PERFORMED_AT, "박직원", cancelled);
	}

	@Test
	@Tag("REQ-26")
	@DisplayName("상세 응답의 연락처는 가운데 자리를 가린 값만 담고 원문은 어디에도 없다")
	void phone_is_masked() {
		// given
		CustomerDetail detail = new CustomerDetail(CUSTOMER_ID, "김단골", "01000005678", new BigDecimal("5500"), true,
				CREATED_AT, List.of(useEntry(false)));

		// when
		CustomerDetailResponse response = CustomerDetailResponse.from(detail);

		// then
		assertThat(response.maskedPhone()).isEqualTo("010-****-5678");
		assertThat(response.toString()).doesNotContain("01000005678");
	}

	@Test
	@Tag("REQ-4")
	@DisplayName("비활성 고객도 상세 응답으로 변환되고 active 가 false 로 담긴다")
	void inactive_customer_is_converted_with_active_false() {
		// given
		CustomerDetail detail = new CustomerDetail(CUSTOMER_ID, "박휴면", "0200001234", BigDecimal.ZERO, false,
				CREATED_AT, List.of());

		// when
		CustomerDetailResponse response = CustomerDetailResponse.from(detail);

		// then
		assertThat(response.active()).isFalse();
		assertThat(response.balance()).isEqualByComparingTo("0");
		assertThat(response.maskedPhone()).isEqualTo("02-****-1234");
	}

	@Test
	@DisplayName("거래가 없는 고객은 최근 거래가 빈 목록이다")
	void customer_without_entries_has_empty_recent_entries() {
		// given: 저장소가 최근 거래 자리에 null 을 넘겨도 빈 목록으로 담는다
		CustomerDetail detail = new CustomerDetail(CUSTOMER_ID, "김단골", "01000005678", BigDecimal.ZERO, true,
				CREATED_AT, null);

		// when
		CustomerDetailResponse response = CustomerDetailResponse.from(detail);

		// then
		assertThat(response.recentEntries()).isNotNull().isEmpty();
	}

	@Test
	@DisplayName("최근 거래는 저장된 금액·부호 금액·직후 잔액·취소 여부를 그대로 옮긴다")
	void recent_entries_are_copied_as_stored() {
		// given
		CustomerDetail detail = new CustomerDetail(CUSTOMER_ID, "김단골", "01000005678", new BigDecimal("10000"), true,
				CREATED_AT, List.of(useEntry(true)));

		// when
		CustomerDetailResponse response = CustomerDetailResponse.from(detail);

		// then
		assertThat(response.recentEntries()).hasSize(1);
		RecentLedgerEntryResponse entry = response.recentEntries().get(0);
		assertThat(entry.type()).isEqualTo(LedgerEntryType.USE);
		assertThat(entry.amount()).isEqualByComparingTo("4500");
		assertThat(entry.signedAmount()).isEqualByComparingTo("-4500");
		assertThat(entry.balanceAfter()).isEqualByComparingTo("5500");
		assertThat(entry.memo()).isEqualTo("음료");
		assertThat(entry.performedAt()).isEqualTo(PERFORMED_AT);
		assertThat(entry.performedByName()).isEqualTo("박직원");
		assertThat(entry.cancelled()).isTrue();
	}

}

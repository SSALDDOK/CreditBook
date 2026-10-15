package com.creditbook.customer.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.creditbook.prepaid.domain.LedgerEntryType;

/**
 * 고객 상세 (REQ-11) — 고객·잔액과 최근 거래를 한 SQL 문장으로 읽은 조회 전용 값. 엔티티가 아니므로 이것으로 상태를 바꾸지 않는다.
 * <p>
 * 잔액({@code balance})과 최근 거래의 {@code balanceAfter} 는 같은 문장에서 읽어 같은 스냅샷이다 — 화면이 둘을 나란히 보여 줘도
 * 서로 어긋나지 않는다. 여기서 잔액을 계산하지 않는다 (DB 에 저장된 값을 그대로 담는다).
 *
 * @param phone 저장된 원문(숫자 정규형). 응답으로 내보낼 때는 반드시 {@link PhoneNumberMasker} 를 거친다
 * @param balance prepaid_accounts.balance (원 단위 정수)
 * @param active 활성 고객이면 true. 비활성 고객도 상세 조회는 된다 (REQ-4)
 * @param recentEntries 최근 거래 (seq 내림차순, 최신이 0번). 거래가 없으면 빈 목록
 */
public record CustomerDetail(
		UUID id,
		String name,
		String phone,
		BigDecimal balance,
		boolean active,
		Instant createdAt,
		List<RecentEntry> recentEntries) {

	public CustomerDetail {
		Objects.requireNonNull(id, "id");
		recentEntries = recentEntries == null ? List.of() : List.copyOf(recentEntries);
	}

	/**
	 * 최근 거래 한 건. ledger_entries 행을 그대로 옮긴 값이다.
	 *
	 * @param signedAmount DB 생성 컬럼 signed_amount (잔액에 더해진 부호 있는 금액)
	 * @param performedByName 처리 직원 이름 (직원 행을 찾지 못하면 null)
	 * @param cancelled 이 거래를 반제한 행이 있으면 true. 반제 행 자신은 다시 반제할 수 없으므로 항상 false
	 */
	public record RecentEntry(
			UUID id,
			LedgerEntryType type,
			BigDecimal amount,
			BigDecimal signedAmount,
			BigDecimal balanceAfter,
			String memo,
			Instant performedAt,
			String performedByName,
			boolean cancelled) {
	}

}

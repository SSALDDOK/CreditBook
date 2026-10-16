package com.creditbook.prepaid.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;

/**
 * 거래 이력 한 건 — ledger_entries 행에 처리 직원 이름과 취소 여부를 붙인 조회 전용 값. 엔티티가 아니므로 이것으로 상태를 바꾸지 않는다.
 * 금액은 DB 에 저장된 값을 그대로 담는다 (여기서 계산하지 않는다).
 *
 * @param signedAmount DB 생성 컬럼 signed_amount (잔액에 더해진 부호 있는 금액 — USE·CHARGE_CANCEL 은 음수)
 * @param balanceAfter 이 거래 직후 잔액
 * @param memo 메모. 반제 행이면 취소 사유
 * @param performedByName 처리 직원 이름 (직원 행을 찾지 못하면 null)
 * @param cancelled 이 거래를 반제한 행이 있으면 true. 반제 행 자신은 다시 반제할 수 없으므로 항상 false
 * @param reversesId 반제 행이면 원거래 ID, 아니면 null
 */
public record LedgerHistoryEntry(
		UUID id,
		LedgerEntryType type,
		BigDecimal amount,
		BigDecimal signedAmount,
		BigDecimal balanceAfter,
		String memo,
		Instant performedAt,
		String performedByName,
		boolean cancelled,
		UUID reversesId) {

	public LedgerHistoryEntry {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(performedAt, "performedAt");
	}

	/**
	 * 이 거래를 {@code now} 에 취소할 수 있는가. 판단은 {@link LedgerEntry#canBeCancelledAt} 가 한다 —
	 * 충전 취소의 '당일' 규칙과 같은 판단을 쓴다. 잔액은 보지 않는다.
	 *
	 * @param now 판단 시각 (서버 시각)
	 * @param businessZone 영업 시간대 (creditbook.business-zone)
	 */
	public boolean canBeCancelledAt(Instant now, ZoneId businessZone) {
		return LedgerEntry.canBeCancelledAt(type, performedAt, cancelled, now, businessZone);
	}

}

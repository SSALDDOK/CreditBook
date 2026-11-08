package com.creditbook.prepaid.infrastructure;

import java.util.List;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.creditbook.prepaid.domain.LedgerReconciliation;
import com.creditbook.prepaid.domain.ReconciliationResult;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * {@link LedgerReconciliation}의 네이티브 SQL 구현.
 * <p>
 * 쿼리는 DB 스키마 정의서 §6(운영 점검 가이드 §2.1·§2.2)의 원문 그대로다. 문서가 바뀌면 여기와
 * {@code ReconciliationApiIntegrationTest}의 같은 쿼리를 함께 고친다. 각 쿼리를 {@code count(*)}로 감싸
 * 건수만 읽는다 — §2.1이 고르는 고객 이름은 애플리케이션으로 넘어오지 않는다.
 * <p>
 * 읽기 전용 트랜잭션(PgBouncer 트랜잭션 모드에서도 세션 설정 없이 {@code BEGIN READ ONLY}로 처리됨)과
 * 시간 제한을 둔다. 기동 중에 돌기 때문에 DB가 느려도 기동이 무한정 늦어지지 않게 한다.
 */
@Repository
class LedgerReconciliationImpl implements LedgerReconciliation {

	private static final int TIMEOUT_SECONDS = 10;

	private record Check(String id, String sql) {
	}

	private static final List<Check> CHECKS = List.of(
			// §2.1 계좌 잔액과 거래 합계가 다른 계좌
			new Check("balance_vs_ledger", """
					SELECT a.id AS account_id, c.name,
					       a.balance                          AS stored,
					       COALESCE(SUM(t.signed_amount), 0)  AS computed,
					       a.balance - COALESCE(SUM(t.signed_amount), 0) AS diff
					FROM prepaid_accounts a
					JOIN customers c ON c.id = a.customer_id
					LEFT JOIN ledger_entries t ON t.account_id = a.id
					GROUP BY a.id, c.name, a.balance
					HAVING a.balance <> COALESCE(SUM(t.signed_amount), 0)
					"""),
			// §2.2 음수 잔액 (CHECK 제약이 막지만 이중 확인)
			new Check("negative_balance", """
					SELECT id, balance FROM prepaid_accounts WHERE balance < 0
					"""),
			// §2.2 balance_after 누적합 불일치 — seq 순서로 누적한 값과 각 거래의 '거래 후 잔액' 비교
			new Check("balance_after_running_sum", """
					WITH running AS (
					    SELECT id, account_id, seq, performed_at, balance_after,
					           SUM(signed_amount) OVER (
					               PARTITION BY account_id
					               ORDER BY seq
					               ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
					           ) AS expected
					    FROM ledger_entries
					)
					SELECT id, account_id, seq, performed_at, balance_after, expected,
					       balance_after - expected AS diff
					FROM running
					WHERE balance_after <> expected
					ORDER BY account_id, seq
					"""),
			// §2.2 반제인데 원본 참조가 없는 경우 (CHECK 제약이 막지만 이중 확인)
			new Check("cancel_without_origin", """
					SELECT id, type FROM ledger_entries
					WHERE type IN ('CHARGE_CANCEL', 'USE_CANCEL') AND reverses_id IS NULL
					"""),
			// §2.2 반제 금액 ≠ 원본 금액
			new Check("cancel_amount_mismatch", """
					SELECT r.id AS cancel_id, r.amount AS cancel_amount, o.amount AS origin_amount
					FROM ledger_entries r
					JOIN ledger_entries o ON o.id = r.reverses_id
					WHERE r.amount <> o.amount
					"""),
			// §2.2 반제 대상 유형·계좌 불일치
			new Check("cancel_target_mismatch", """
					SELECT r.id AS cancel_id, r.type, o.type AS origin_type, r.account_id, o.account_id AS origin_account
					FROM ledger_entries r
					JOIN ledger_entries o ON o.id = r.reverses_id
					WHERE r.account_id <> o.account_id
					   OR (r.type = 'CHARGE_CANCEL' AND o.type <> 'CHARGE')
					   OR (r.type = 'USE_CANCEL'    AND o.type <> 'USE')
					"""));

	@PersistenceContext
	private EntityManager em;

	@Override
	@Transactional(readOnly = true, timeout = TIMEOUT_SECONDS)
	public List<ReconciliationResult> runAll() {
		return CHECKS.stream().map(check -> new ReconciliationResult(check.id(), count(check.sql()))).toList();
	}

	private long count(String sql) {
		Object result = em.createNativeQuery("SELECT count(*) FROM (" + sql + ") q").getSingleResult();
		return ((Number) result).longValue();
	}
}

package com.creditbook.prepaid.application;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.creditbook.prepaid.domain.LedgerReconciliation;
import com.creditbook.prepaid.domain.ReconciliationResult;

/**
 * 기동할 때마다 장부 정합성 점검을 돌려 결과를 로그로 남긴다 (REQ-28, CB-22).
 * <p>
 * 배포마다(무료 호스팅이면 잠들었다 깨어날 때마다) 백엔드가 다시 기동하므로 '배포 직후 자동 점검'이 된다.
 * 모두 0건이면 INFO, 틀린 행이 있으면 점검마다 ERROR — 건수만 남기고 고객 이름·연락처는 남기지 않는다.
 * 원인 찾기는 운영자가 운영 점검 가이드 §2·§3 쿼리로 한다.
 * <p>
 * 점검 실행이 실패해도 기동은 막지 않는다 — 점검 때문에 서비스가 뜨지 않으면 안 된다. 그래서 트랜잭션은
 * {@link LedgerReconciliation} 구현 쪽에 두고, 여기서는 트랜잭션 없이 그 바깥에서 예외를 잡는다
 * (트랜잭션 시작 실패도 이 catch에 잡힌다).
 */
@Component
@ConditionalOnProperty(prefix = "creditbook.reconciliation", name = "on-startup", havingValue = "true",
		matchIfMissing = true)
class ReconciliationStartupRunner implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(ReconciliationStartupRunner.class);

	private final LedgerReconciliation reconciliation;

	ReconciliationStartupRunner(LedgerReconciliation reconciliation) {
		this.reconciliation = reconciliation;
	}

	@Override
	public void run(ApplicationArguments args) {
		List<ReconciliationResult> results;
		try {
			results = reconciliation.runAll();
		}
		catch (RuntimeException e) {
			log.error("reconciliation check failed on startup (startup continues)", e);
			return;
		}

		List<ReconciliationResult> mismatches = results.stream().filter(result -> !result.isClean()).toList();
		if (mismatches.isEmpty()) {
			log.info("reconciliation check ok on startup: checks={}, rows=0", results.size());
			return;
		}
		for (ReconciliationResult mismatch : mismatches) {
			log.error("reconciliation mismatch on startup: check={}, rows={}", mismatch.checkId(), mismatch.rows());
		}
	}
}

package com.creditbook.prepaid.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.QueryTimeoutException;

import com.creditbook.prepaid.domain.LedgerReconciliation;
import com.creditbook.prepaid.domain.ReconciliationResult;

/**
 * 기동 시 정합성 점검 (CB-22). 스프링 없이 점검 결과만 바꿔 가며 로그 레벨·문구와 '실패해도 기동 계속'을 본다.
 * 쿼리 자체가 실제 DB에서 0건을 내는지는 TC-52 통합 테스트가 본다.
 */
@ExtendWith({ MockitoExtension.class, OutputCaptureExtension.class })
@Tag("REQ-28")
class ReconciliationStartupRunnerTest {

	@Mock
	private LedgerReconciliation reconciliation;

	private ReconciliationStartupRunner runner() {
		return new ReconciliationStartupRunner(reconciliation);
	}

	private static void run(ReconciliationStartupRunner runner) {
		runner.run(new DefaultApplicationArguments());
	}

	@Test
	@DisplayName("점검이 모두 0건이면 INFO 한 줄을 남기고 ERROR는 남기지 않는다")
	void logs_ok_when_all_checks_are_clean(CapturedOutput output) {
		// given
		given(reconciliation.runAll()).willReturn(List.of(new ReconciliationResult("balance_vs_ledger", 0),
				new ReconciliationResult("negative_balance", 0)));

		// when
		run(runner());

		// then
		assertThat(output.getOut().lines().filter(line -> line.contains("reconciliation check ok")))
				.singleElement()
				.asString()
				.contains("INFO", "checks=2", "rows=0");
		assertThat(output.getOut()).doesNotContain("reconciliation mismatch");
	}

	@Test
	@DisplayName("틀린 행이 있는 점검마다 점검 이름과 건수를 ERROR로 남기고, 정상 안내는 남기지 않는다")
	void logs_error_per_mismatched_check(CapturedOutput output) {
		// given
		given(reconciliation.runAll()).willReturn(List.of(new ReconciliationResult("balance_vs_ledger", 2),
				new ReconciliationResult("negative_balance", 0),
				new ReconciliationResult("cancel_amount_mismatch", 1)));

		// when
		run(runner());

		// then
		List<String> mismatchLines = output.getOut()
				.lines()
				.filter(line -> line.contains("reconciliation mismatch"))
				.toList();
		assertThat(mismatchLines).hasSize(2);
		assertThat(mismatchLines.get(0)).contains("ERROR", "check=balance_vs_ledger", "rows=2");
		assertThat(mismatchLines.get(1)).contains("ERROR", "check=cancel_amount_mismatch", "rows=1");
		assertThat(output.getOut()).doesNotContain("check=negative_balance").doesNotContain("reconciliation check ok");
	}

	@Test
	@DisplayName("점검 실행이 실패해도 예외를 밖으로 던지지 않고(기동 계속) ERROR를 남긴다")
	void keeps_startup_going_when_check_fails(CapturedOutput output) {
		// given
		given(reconciliation.runAll()).willThrow(new QueryTimeoutException("timeout"));
		ReconciliationStartupRunner runner = runner();

		// when / then
		assertThatCode(() -> run(runner)).doesNotThrowAnyException();
		assertThat(output.getOut().lines().filter(line -> line.contains("reconciliation check failed")))
				.singleElement()
				.asString()
				.contains("ERROR");
		assertThat(output.getOut()).doesNotContain("reconciliation check ok");
	}
}

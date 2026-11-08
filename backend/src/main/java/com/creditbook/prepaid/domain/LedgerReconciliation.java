package com.creditbook.prepaid.domain;

import java.util.List;

/**
 * 장부 정합성 점검 (REQ-28). 운영 점검 가이드 §2 쿼리(확정본은 DB 스키마 정의서 §6)를 모두 실행해
 * 점검마다 '틀린 행' 건수를 돌려준다. 정상 장부면 모두 0이다.
 * <p>
 * 읽기만 한다 — 틀린 행을 찾아도 고치지 않는다. 보정은 운영 점검 가이드 §4(보정 거래 추가)로만 한다.
 */
public interface LedgerReconciliation {

	List<ReconciliationResult> runAll();
}

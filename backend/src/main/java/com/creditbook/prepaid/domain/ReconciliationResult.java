package com.creditbook.prepaid.domain;

/**
 * 정합성 점검 하나의 결과. {@code rows}는 그 점검이 찾은 '틀린 행' 건수라 0이어야 정상이다.
 * 고객 이름·연락처 같은 행 내용은 담지 않는다 — 건수만 로그로 남긴다.
 */
public record ReconciliationResult(String checkId, long rows) {

	public boolean isClean() {
		return rows == 0;
	}
}

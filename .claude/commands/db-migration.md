---
description: Flyway 마이그레이션 작성 워크플로우 (ledger_entries append-only 규칙 강제)
argument-hint: <마이그레이션 목적 설명>
---

# /db-migration

CreditBook의 스키마 변경은 Flyway 마이그레이션으로만 한다. Neon(Postgres)에 첫 실행 시 `V1__init.sql`부터 순서대로 적용된다.

**입력**: $ARGUMENTS

## 1단계 — 현재 상태 파악

```bash
ls src/main/resources/db/migration/ 2>/dev/null | sort
```
가장 최근 버전 번호를 확인하고, 다음 번호로 `V{n}__snake_case_description.sql` 파일명을 정한다. 기존 `V__` 파일은 **절대 수정하지 않는다** — 이미 적용된 마이그레이션 수정은 체크섬 오류를 낸다.

## 2단계 — 변경 대상이 append-only 테이블인지 확인

`ledger_entries`를 건드리는 변경이라면:
- 컬럼 추가는 가능하나 기존 행의 데이터를 바꾸는 `UPDATE`문은 마이그레이션에 절대 포함하지 않는다
- `signed_amount`(GENERATED STORED), `seq`(BIGSERIAL) 컬럼 정의를 건드리는 경우 특히 신중히 — 이 두 컬럼은 애플리케이션이 값을 채우지 않는다는 전제가 JPA 매핑(`insertable=false, updatable=false`)에도 반영돼 있어야 한다

## 3단계 — 네이밍/타입 규칙 준수

- 제약조건: `ck_`(CHECK) / `ux_`(UNIQUE) / `ix_`(INDEX) + 테이블명 + 의미. 예: `ck_ledger_entries_amount`
- 테이블·컬럼: 복수형 snake_case
- ID: `UUID DEFAULT gen_random_uuid()`
- 금액: `NUMERIC(12,0)` (`double`/`float`/`money` 금지)
- 시각: `TIMESTAMPTZ NOT NULL DEFAULT now()` (`timestamp` 타임존 없는 타입 금지)
- FK를 추가하면 같은 마이그레이션에서 인덱스도 함께 만든다 (Postgres는 FK에 자동 인덱스를 생성하지 않는다)

## 4단계 — 마이그레이션 파일 작성

Read 도구로 `V1__init.sql`을 참고해 스타일(대문자 SQL 키워드, 제약조건 인라인 vs 별도 `CREATE INDEX`)을 맞춘다.

## 5단계 — 검증

```bash
./gradlew flywayValidate 2>&1 || ./mvnw flyway:validate 2>&1
./gradlew test 2>&1   # Testcontainers 통합 테스트가 새 스키마로 통과하는지
```
H2가 아니라 Testcontainers 기반 통합 테스트로 검증해야 `GENERATED`/partial unique index 동작을 신뢰할 수 있다.

## 6단계 — 리뷰

이 워크플로우 자체가 `backend-dev` 에이전트 범위 안이다(Flyway 마이그레이션 작성 포함). 작성 후 `backend-dev`의 "완료 전 self-check"로 제약조건 네이밍, append-only 위반 여부, 인덱스 커버리지를 다시 확인한다. S3부터 `code-reviewer`가 생기면 병합 전 2차 검토를 맡긴다.

## 7단계 — 요약

```text
파일: V{n}__{description}.sql
변경: [테이블/컬럼/제약조건 요약]
append-only 영향: 없음 / [설명]
검증: flywayValidate 통과, 통합 테스트 통과
```

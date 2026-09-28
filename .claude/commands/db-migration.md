---
description: Flyway 마이그레이션 작성 워크플로 (ledger_entries append-only 규칙 강제, Neon 롤백 검증, 기술 설계 협업 흐름)
argument-hint: <마이그레이션 목적 설명>
---

# /db-migration

CreditBook의 스키마 변경은 Flyway 마이그레이션으로만 한다. 앱이 뜰 때 Flyway가 아직 적용되지 않은 `V{n}__*.sql`을 번호 순서대로 적용한다.
스키마는 기술 설계이므로 CLAUDE.md "기술 설계는 리포가 먼저" 흐름을 따른다 — **설계 → Neon 롤백 검증 → 사용자 승인 → 구현 → 인수인계 요청**.

**입력**: $ARGUMENTS

## 1단계 — 현재 상태 파악

```bash
ls backend/src/main/resources/db/migration/ | sort
```

다음 둘 중 어느 경우인지 먼저 판단한다:

| 상황 | 할 일 |
|---|---|
| 대상 파일이 **아직 어느 DB에도 적용되지 않음** (CLAUDE.md "DB 스키마" 절에 "미적용"으로 적혀 있음) | 새 번호를 만들지 않고 **그 파일을 직접 고친다** |
| 이미 한 곳이라도 적용됨 (Neon의 `flyway_schema_history`에 기록 있음) | 다음 번호로 `V{n}__snake_case_description.sql`을 새로 만든다. 적용된 파일은 **절대 수정하지 않는다** — 체크섬 오류가 나고 환경 간 스키마가 어긋난다 |

적용 여부가 애매하면 추측하지 말고 사용자에게 묻는다.

## 2단계 — 변경 대상이 append-only 테이블인지 확인

`ledger_entries`를 건드리는 변경이라면:
- 기존 행의 데이터를 바꾸는 `UPDATE`·`DELETE`는 마이그레이션에 넣을 수 없다 — 트리거(`trg_ledger_entries_no_update_delete`, `trg_ledger_entries_no_truncate`)가 거절한다. 트리거를 끄거나 지우는 마이그레이션도 금지(절대 금지 1번)
- 컬럼 추가는 가능하다. 백필이 필요하면 설계를 다시 한다(append-only 테이블은 백필할 수 없다)
- `signed_amount`(GENERATED STORED), `seq`(BIGSERIAL) 정의를 건드리면 JPA 매핑(`insertable=false, updatable=false`)도 함께 확인한다

## 3단계 — 네이밍/타입 규칙

- 제약조건: `ck_`(CHECK) / `ux_`(UNIQUE) / `ix_`(INDEX) + 테이블명 + 의미. **인라인 `UNIQUE`를 쓰지 않는다** — 자동 이름(`*_key`)이 붙는다. `CONSTRAINT ux_... UNIQUE (col)`로 명명한다
- 트리거·함수: `trg_` / `fn_` + 테이블명 + 의미
- 테이블·컬럼: 복수형 snake_case
- ID: `UUID DEFAULT gen_random_uuid()`
- 금액: `NUMERIC(12,0)` (`double`/`float`/`money` 금지)
- 시각: `TIMESTAMPTZ NOT NULL DEFAULT now()` (타임존 없는 `timestamp` 금지)
- FK를 추가하면 인덱스도 **원칙적으로** 함께 만든다(PostgreSQL은 FK에 인덱스를 자동으로 만들지 않는다). 쓰이는 경로(참조 대상 삭제·키 변경, FK 기준 조회)가 없으면 생략할 수 있으나 **근거를 CLAUDE.md와 인수인계 요청에 남긴다** (예: `performed_by`, `accessed_by`)

## 4단계 — 마이그레이션 작성

`V1__init.sql`의 스타일(대문자 키워드, 명명된 제약, 별도 `CREATE INDEX`)을 따른다. 방법론은 `database-migrations` skill을 참고한다.

## 5단계 — 검증

1. **Neon 롤백 전용 검증** — 로컬 Docker가 없을 수 있으므로 실제 PostgreSQL 동작은 여기서 확인한다
   - JDK `jshell` + Gradle 캐시의 postgresql JDBC jar로 **direct 연결**(`spring.flyway.url`)에 접속
   - 비밀번호는 `System.getenv("CREDITBOOK_DB_PASSWORD")`로만 읽는다. 출력·파일 저장·명령줄 인자 금지
   - `setAutoCommit(false)` → 마이그레이션 SQL 실행 → 시나리오를 SAVEPOINT로 하나씩 확인(제약 위반은 **제약 이름**까지) → **반드시 `rollback()`**. commit 금지
   - 새 연결로 `to_regclass(...)` 등을 조회해 **남은 객체가 없는지** 확인
2. **Testcontainers 통합 테스트** — 새 제약·트리거마다 위반 시 제약 이름/SQLSTATE까지 확인하는 테스트를 추가한다(`@RequiresDocker` + `TestcontainersConfiguration`)
   ```bash
   cd backend && ./gradlew build
   ```
   Docker가 없으면 테스트는 **스킵**된다. 스킵을 통과로 보고하지 않는다 — Neon 롤백 검증 결과와 함께 "CI에서 실행 예정"으로 보고한다
3. **`bootRun`은 실행하지 않는다** — Flyway가 Neon에 실제로 적용해 버린다. 적용 시점은 사용자와 정한다

H2로 검증하지 않는다 — `GENERATED` 컬럼·부분 유니크 인덱스·트리거 동작이 운영과 다르다.

## 6단계 — 사용자 승인과 기록

1. 설계안(SQL, 근거, 검증 결과)을 채팅으로 제시하고 **사용자 승인**을 받는다
2. 승인되면 커밋한다. CLAUDE.md "DB 스키마" 절은 **SQL을 복사하지 않고** 테이블 요약표(제약·인덱스·트리거 이름)와 결정 메모(왜 그렇게 했는지)만 갱신한다. 적용 여부 표시("미적용")도 바뀌었으면 고친다
3. 인수인계 요청 DB에 요청을 쓴다 — 스키마 정의서의 **절 구조(§1 결정 표, §3 테이블, §4 SQL, §4.1 대응표, §6 정합성 쿼리, §7 변경 이력)에 그대로 들어갈 문구**로 쓴다. 개발 컨벤션·운영 점검 가이드에 영향이 있으면 그 절도 함께 적는다

## 7단계 — 요약

```text
파일: V{n}__{description}.sql (신규 / 미적용 파일 직접 수정)
변경: [테이블/컬럼/제약조건 요약]
append-only 영향: 없음 / [설명]
Neon 롤백 검증: 시나리오 N건 PASS, 잔여 객체 없음
통합 테스트: 실행 N / 스킵 N / 실패 N
인수인계 요청: [링크]
```

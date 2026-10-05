---
description: DDD 계층 순서(domain→application→controller→infrastructure)로 기능을 구현하는 TDD 워크플로, GitHub Flow 브랜치/커밋/PR 규칙 포함
argument-hint: <Jira 키> <기능 설명>
---

# /feature-dev

CreditBook에서 새 기능을 구현하는 표준 절차다. **입력**: $ARGUMENTS (예: `CB-42 충전 API`)

## 누가 어느 단계를 하나

| 단계 | 담당 |
|---|---|
| 0 브랜치, 5 `/cb-review-gate`, 6 커밋, 7 PR | Claude Code (CLAUDE.md "병합 역할") |
| 1–4 설계·테스트·구현·빌드, 5의 자가 점검 | 서버는 `backend-dev`, 화면은 `frontend-dev`에게 위임. 작은 작업은 Claude Code가 직접 |

`backend-dev`·`frontend-dev`는 이 커맨드를 미리 불러온 상태로 시작한다(에이전트 정의의 `skills`). 위임받으면 1–5단계를 하고 **커밋·push·PR 없이** 결과를 보고한다. 화면 기능이면 1–4단계 대신 아래 "화면 기능일 때" 절을 따른다.

## 0단계 — 브랜치

```bash
git checkout main && git pull --ff-only
git checkout -b feature/CB-42-charge-api   # $ARGUMENTS에서 Jira 키를 추출해 규칙에 맞게 구성
git push -u origin feature/CB-42-charge-api   # 일찍 push — Jira "브랜치 생성 → 진행 중" 자동화
```
이슈마다 `main`에서 딴다(쌓인 PR 쓰지 않음). `main`에 직접 커밋하지 않는다 — GitHub Flow만 사용, `develop` 브랜치 없음.

## 1단계 — domain부터 설계

잔액에 영향을 주는 기능이면 반드시 `PrepaidAccount`(또는 관련 도메인 객체) 안에 새 메서드/검증을 추가하는 것으로 시작한다. 서비스 계층에서 산술을 하도록 설계하지 않는다.

- **API 명세가 새로 생기거나 바뀌면**(경로·요청/응답 필드·상태 코드·에러 코드) `api-design` skill로 검토하고, 추천안 하나를 실제 JSON 예시와 함께 사용자에게 제시해 **승인받은 뒤** 구현한다. 에이전트 혼자 확정하지 않는다. 오류 응답은 `ErrorResponse`·`ErrorCode`가 확정본이다
- 스키마 변경이 필요하면 `/db-migration`으로 먼저 설계·검증·승인을 받는다
- 업무 규칙(요구사항·취소 조건·권한 정책) 자체를 바꿔야 하면 멈추고 보고한다 — Notion이 먼저다

## 2단계 — 테스트 먼저 (TDD)

`springboot-tdd` skill과 `backend-dev`의 "테스트" 규칙을 따른다 (S3부터 `test-writer`가 생기면 실행·검증을 그쪽과 나눈다):
- **먼저 Notion 테스트 케이스 DB에서 그 REQ의 TC를 찾고 테스트 계획서를 확인한다.** TC가 정한 레벨(단위·통합·E2E)·유형대로 쓴다. 위임할 때는 Claude Code가 TC ID·레벨·이름을 프롬프트에 적어 넘긴다(에이전트는 Notion을 못 본다). 인수조건은 요구사항 DB가 기준이다
- domain 단위 테스트는 스프링 없이 순수 객체로, Given-When-Then + 한글 `@DisplayName`(인수조건 문장 그대로) + `@Tag("REQ-xxx")` + TC를 검증하면 `@Tag("TC-n")`
- 경계값은 `@ParameterizedTest`로 모은다
- `BigDecimal`은 `isEqualByComparingTo`로만 비교

## 3단계 — 계층 순서대로 구현

1. `domain` — 엔티티, 불변식, 도메인 예외 (`java-coding-standards`, `jpa-patterns`)
2. `application` — `@Transactional` 유스케이스, 권한 확인 (산술 로직 없음)
3. `controller` — Request/Response DTO, 입력 형식 검증 (비즈니스 판단 없음). 새 도메인 예외는 `GlobalExceptionHandler`에서 상태 코드로 매핑한다 — 매핑이 빠지면 500이 된다
4. `infrastructure` — JPA 리포지토리 구현체 (`ledger_entries`는 `persist`로만 추가)

## 4단계 — 빌드/테스트 확인

```bash
cd backend && ./gradlew build   # 실행·스킵·실패 수를 확인한다 (Docker 없으면 통합 테스트는 스킵 — 통과로 보지 않는다)
```
주석·Javadoc만 바뀌면 test가 UP-TO-DATE로 건너뛰므로 `./gradlew test --rerun`으로 다시 돌린다.

## 화면 기능일 때 (frontend-dev) — 1–4단계 대신

1. **기준 확인** — 위임할 때 Claude Code가 Notion 화면 목록 DB·화면설계서에서 화면 ID(SCR-n)·라우트·인수조건·"오류 메시지 표준" 문구와 대상 TC를 찾아 프롬프트에 적는다(에이전트는 Notion을 못 본다). 쓸 API가 아직 확정되지 않았으면 화면을 먼저 만들지 않는다 — API 명세 승인이 먼저다
2. **테스트 준비** — E2E·통합 테스트는 사용자가 작성한다. frontend-dev는 테스트가 붙을 `data-testid`와 안정적인 로딩 상태를 만들고, 결과 보고에 testid 목록을 적는다. `e2e-testing` skill의 페이지 객체 구성을 염두에 둔다
3. **구현 순서** — `src/api`(호출·요청/응답 타입) → 라우트·인증 처리 → 화면·컴포넌트 → 서버 오류 `code`별 문구 연결 (`react-patterns`, 폼·모달은 `frontend-a11y`). 금액은 계산하지 않고 서버 응답을 표시한다. 새 라이브러리는 추천안으로 사용자 승인 뒤 설치한다
4. **빌드 확인** — `cd frontend && npm run build && npm run lint` (`build`에 `tsc -b` 포함 — `vite-patterns`). 실패한 채로 끝냈다고 보고하지 않는다

## 5단계 — 자가 리뷰

- backend-dev·frontend-dev: 각 에이전트 정의의 "완료 전 자가 점검" 체크리스트
- Claude Code: 위임 결과를 테스트 재실행으로 확인한 뒤 `/cb-review-gate`로 절대 금지 8항목을 게이트한다. S3부터 `code-reviewer`(읽기 전용, OWASP 보안 체크)가 생기면 PR 단계 리뷰를 그쪽으로 넘긴다

## 6단계 — 커밋

```text
feat(CB-42): 충전 API 추가
```
`type`은 feat/fix/test/refactor/docs/chore 중 하나, 메시지는 Jira 키 포함 한 줄 요약. PowerShell 파이프로 메시지를 넘기지 않는다(BOM) — 파일로 만들어 `git commit -F`로 넘긴다.

## 7단계 — PR

혼자 작업해도 PR을 거친다 (CI 통과 확인 + 변경 이력 보존 목적). CI 실패 상태에서 병합하지 않는다.

```bash
git push
gh pr create --title "CB-42 충전 API 추가" --body-file <파일>
```

- PR 제목 **맨 앞에 Jira 키**. 완료 여부와 관계없이 그 PR이 다룬 이슈 키를 모두 적는다 — Jira 완료 처리는 사용자가 직접 확인한 뒤 수동으로 하므로("PR 병합 → 완료" 자동화 없음) 키가 이슈를 일찍 닫지 않는다
- PR 설명에는 코드 변경 내용과 **어느 이슈가 완료 조건을 채웠는지**를 적는다. 도구 이름·진행 사정은 쓰지 않는다 (CLAUDE.md "공개 산출물 표기 규칙")
- 병합(Create a merge commit)은 사용자가 한다. 병합 뒤 `main` 최신화와 브랜치 삭제는 Claude Code

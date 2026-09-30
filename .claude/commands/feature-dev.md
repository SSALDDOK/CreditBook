---
description: DDD 계층 순서(domain→application→controller→infrastructure)로 기능을 구현하는 TDD 워크플로, GitHub Flow 브랜치/커밋/PR 규칙 포함
argument-hint: <Jira 키> <기능 설명>
---

# /feature-dev

CreditBook에서 새 기능을 구현하는 표준 절차다. **입력**: $ARGUMENTS (예: `CB-42 충전 API`)

## 0단계 — 브랜치

```bash
git checkout -b feature/CB-42-charge-api   # $ARGUMENTS에서 Jira 키를 추출해 규칙에 맞게 구성
```
혼자 작업해도 `main`에 직접 커밋하지 않는다 — GitHub Flow만 사용, `develop` 브랜치 없음.

## 1단계 — domain부터 설계

잔액에 영향을 주는 기능이면 반드시 `PrepaidAccount`(또는 관련 도메인 객체) 안에 새 메서드/검증을 추가하는 것으로 시작한다. 서비스 계층에서 산술을 하도록 설계하지 않는다. `backend-dev`에게 위임하면 이 원칙과 계층 경계를 함께 지킨다.

## 2단계 — 테스트 먼저 (TDD)

`backend-dev`의 "테스트" 규칙을 따른다 (S2부터 `test-writer`가 생기면 이 단계를 그쪽으로 넘긴다):
- domain 단위 테스트는 스프링 없이 순수 객체로, Given-When-Then + 한글 `@DisplayName`
- 경계값은 `@ParameterizedTest`로 모은다
- `BigDecimal`은 `isEqualByComparingTo`로만 비교

## 3단계 — 계층 순서대로 구현

1. `domain` — 엔티티, 불변식, 도메인 예외
2. `application` — `@Transactional` 유스케이스, 권한 확인 (산술 로직 없음)
3. `controller` — Request/Response DTO, 입력 형식 검증 (비즈니스 판단 없음)
4. `infrastructure` — JPA 리포지토리 구현체

스키마 변경이 필요하면 `/db-migration`으로 Flyway 마이그레이션을 먼저 작성한다.

## 4단계 — 빌드/테스트 확인

```bash
cd backend && ./gradlew build   # 실행·스킵·실패 수를 확인한다 (Docker 없으면 통합 테스트는 스킵 — 통과로 보지 않는다)
```
실패하면 `backend-dev`에게 위임한다.

## 5단계 — 자가 리뷰

`/cb-review-gate`로 절대 금지 8항목을 먼저 게이트하고, `backend-dev`로 심층 리뷰한다. S3부터 `code-reviewer`(읽기 전용, OWASP 보안 체크)가 생기면 PR 단계 리뷰를 그쪽으로 넘긴다.

## 6단계 — 커밋

```text
feat(CB-42): 충전 API 추가
```
`type`은 feat/fix/test/refactor/docs/chore 중 하나, 메시지는 Jira 키 포함 한 줄 요약.

## 7단계 — PR

혼자 작업해도 PR을 거친다 (CI 통과 확인 + 변경 이력 보존 목적). PR 생성 전 CI가 통과했는지 확인 — CI 실패 상태에서 병합하지 않는다.

```bash
git push -u origin feature/CB-42-charge-api
gh pr create --title "CB-42 충전 API 추가" --body "..."
```

PR 제목은 **맨 앞에 Jira 키**를 붙인다(여러 이슈면 모두). 아직 완료되지 않은 이슈의 키는 PR 제목·커밋에 넣지 않는다 — Jira의 "PR 병합 → 완료" 자동화가 적힌 키를 모두 완료로 바꾼다 (CLAUDE.md "Git / 커밋 규칙").

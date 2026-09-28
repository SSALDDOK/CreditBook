---
description: CLAUDE.md의 절대 금지 8항목을 하드 게이트로 검사하는 로컬 diff 코드 리뷰
argument-hint: [blank for local review]
---

# /cb-review-gate

CreditBook의 uncommitted 변경사항을 CLAUDE.md 기준으로 검토한다. Claude Code 내장 `/code-review`(일반 품질/버그 리뷰)를 대체하지 않고, 그 위에 이 프로젝트 고유의 절대 금지 8항목을 하드 게이트로 얹는 용도다. 두 커맨드를 함께 쓰는 것을 권장한다.

## 1단계 — 변경 파일 확인

```bash
git diff --name-only HEAD
```
변경 파일이 없으면 "리뷰할 변경사항이 없습니다"로 종료.

## 2단계 — 절대 금지 8항목 하드 게이트

변경된 파일 전체를 읽고 아래 8개 중 하나라도 해당하면 **다른 어떤 리뷰 결과와 무관하게 BLOCK**으로 보고한다.

1. `ledger_entries`에 대한 UPDATE/DELETE (SQL, JPQL, 네이티브 쿼리, 리포지토리 메서드 어디든), 또는 DB 권한(REVOKE)·트리거 차단을 풀거나 우회하는 마이그레이션
2. 금액 필드에 `double`/`float`
3. `prepaid_accounts.balance`(또는 대응 도메인 필드)를 `PrepaidAccount` 도메인 객체 밖에서 직접 대입
4. 컨트롤러가 JPA 엔티티를 그대로 응답으로 반환 (DTO 미경유)
5. 시크릿·DB 비밀번호 하드코딩
6. `@Enumerated(EnumType.ORDINAL)`
7. 도메인 로직 변경인데 대응 테스트 없음
8. (이 리뷰 자체가 CI 실행 전이라면) 최근 CI 상태가 실패인 채로 병합하려는 정황

## 3단계 — 심층 리뷰 위임

지금은 서브에이전트가 `backend-dev` 하나뿐이다. `*.java`, DDD 계층 경계, JPA 매핑, Flyway 마이그레이션 관련 변경은 `backend-dev`에게 위임해 자체 점검(파일 내 "완료 전 self-check" 기준)을 받는다.

S3부터 `code-reviewer`(읽기 전용, OWASP 보안 체크 포함)가 추가되면 인증·PII·시크릿·삼켜진 예외 같은 항목은 그쪽으로 위임이 옮겨간다. 그 전까지는 아래 항목을 이 커맨드 안에서 직접 점검한다:
- 평문 비밀번호 비교, JWT 검증 누락
- 고객 전화번호 조회 시 `phone_access_logs` 기록 누락
- 빈 catch 블록, 삼켜진 `OptimisticLockException`

## 4단계 — 결과 종합

다음 형식으로 보고한다:

```text
## 절대 금지 게이트: PASS / BLOCK
[BLOCK인 경우 위반 항목과 파일:줄 나열]

## backend-dev 리뷰 요약
- ...

## 보안/PII 직접 점검 (code-reviewer 생기기 전까지)
- ...

## 종합 판정: Approve / Warning / Block
```

## 참고
- 이 커맨드는 로컬 uncommitted 변경 전용이다. GitHub PR 전체에 대한 깊은 멀티 에이전트 리뷰가 필요하면 `/code-review ultra`(클라우드 리뷰)를 별도로 사용한다.

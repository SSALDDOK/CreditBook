# Claude Code 도구 사용법 — 커맨드 · Skill · 에이전트

CreditBook 리포의 `.claude/`에 들어 있는 도구를 언제, 어떻게 쓰는지 정리한 참고 문서다.
`.claude/` 안의 커맨드·skill·에이전트를 추가하거나 바꾸면 이 문서도 같이 고친다.

## 한눈에 보는 차이

| 종류 | 위치 | 누가 실행하나 | 확실하게 쓰는 법 |
|---|---|---|---|
| **커맨드** | `.claude/commands/` | 사용자가 입력할 때 | `/이름 인자` |
| **Skill** | `.claude/skills/`, 전역 `~/.claude/skills/` | Claude가 작업 내용을 보고 판단해 불러온다 (빠질 수 있음) | 요청에 이름을 적는다 — "jpa-patterns 참고해서 ~" |
| **에이전트** | `.claude/agents/` | 사용자가 요청할 때만 | "backend-dev 에이전트로 ~ 해줘" |

**원칙**: 규칙을 강제해야 하는 단계는 커맨드로 직접 부르고, skill은 평소엔 맡기되 특정 기준을 꼭 따르게 하고 싶을 때 이름을 적는다.

---

## 커맨드 — 작업 단계마다 직접 입력

| 커맨드 | 언제 | 하는 일 | 입력 예 |
|---|---|---|---|
| `/feature-dev` | 도메인 기능을 새로 만들 때 | 브랜치 생성 → 도메인 설계 → 테스트 먼저(TDD) → domain → application → controller → infrastructure 순서로 구현 → 빌드 → 자가 리뷰 → 커밋 → PR | `/feature-dev CB-8 사용 취소 도메인 로직` |
| `/db-migration` | 스키마를 바꿀 때 | 새 파일인지 미적용 파일 수정인지 판단 → `ledger_entries` append-only 영향 점검 → 네이밍·타입 규칙 적용 → SQL 작성 → **Neon 롤백 검증**(실행 후 전부 되돌림) + 통합 테스트 → **사용자 승인** → 커밋 → Notion 반영용 인수인계 요청 | `/db-migration 고객 등급 컬럼 추가` |
| `/cb-review-gate` | 커밋·PR 직전 | 커밋 전 변경을 **절대 금지 8항목**으로 검사, 하나라도 걸리면 차단(BLOCK). 보안·개인정보 항목도 점검 | `/cb-review-gate` (인자 없음) |

### 권장 흐름

```
/feature-dev CB-xx 기능 설명
   └─ 스키마 변경이 필요하면 → /db-migration 설명
/cb-review-gate            ← 절대 금지 8항목 게이트
/code-review               ← (내장) 일반 버그·품질 리뷰, 함께 쓰면 좋다
PR 생성 → CI 통과 확인 → 병합
```

> 아직 어느 DB에도 적용되지 않은 마이그레이션 파일은 새 번호를 만들지 않고 직접 고친다. 적용 여부는 CLAUDE.md "DB 스키마" 절에 적혀 있다.
>
> 스키마·기술 설계는 **Claude Code가 설계·검증 → 사용자가 채팅에서 승인 → 리포에 먼저 반영 → 코워크가 Notion에 옮겨 적기** 순서로 진행한다 (CLAUDE.md "기술 설계는 리포가 먼저").

---

## Skill — 평소엔 맡기고, 필요할 때 이름을 적는다

### 프로젝트 skill (`.claude/skills/`)

| skill | 이런 작업에 | 주로 쓰는 시기 |
|---|---|---|
| `java-coding-standards` | Java 코드 작성·리뷰 전반 (네이밍, 불변성, 예외, Optional) | S2 이후 계속 |
| `springboot-patterns` | 컨트롤러·서비스 구조, 예외 처리, 로깅 | S2–S3 |
| `jpa-patterns` | 엔티티 매핑, 연관관계, N+1, 트랜잭션 | S2 엔티티 작업 |
| `database-migrations` | 안전한 스키마 변경 원칙, expand-contract | 마이그레이션 작성 시 |
| `postgres-patterns` | 인덱스 선택, 쿼리 최적화, 느린 쿼리 찾기 | 조회 성능 점검 시 |
| `springboot-tdd` | 단위·웹 슬라이스·통합(Testcontainers) 테스트 | S2 정합성 테스트 |
| `springboot-verification` | 빌드 → 정적 분석 → 테스트·커버리지 → 보안 스캔 → diff 점검 | PR 전, 배포 전 |
| `api-design` | URL 네이밍, 상태 코드, 페이지네이션, 오류 응답 형식 | S3 REST API |
| `springboot-security` | JWT, 인가(@PreAuthorize), CORS, 레이트 리밋 | S3 인증, S5 보안 점검 |
| `cb-security-checklist` | 배포 전 보안 체크리스트 (원칙 위주) | S5 |
| `e2e-testing` | Playwright, 페이지 객체 모델, 불안정 테스트 대응 | S3–S4 E2E |
| `git-workflow` | 브랜치·커밋 규칙, rebase, 충돌 해결 | 필요할 때 |

요청 예:
- "jpa-patterns 참고해서 PrepaidAccount 엔티티 매핑해줘"
- "springboot-verification 순서대로 PR 전 점검 돌려줘"
- "api-design 체크리스트로 충전 API 설계 검토해줘"

### 전역 skill (모든 프로젝트 공통)

| skill | 하는 일 |
|---|---|
| `archify` | 아키텍처·워크플로·시퀀스·데이터 흐름·상태 다이어그램을 독립 HTML로 만든다 (다크/라이트, PNG·SVG 내보내기) |
| `archify-review` | archify 자체의 이슈·PR 검토용 — 이 프로젝트에서는 거의 쓰지 않는다 |

archify 요청 예 (포트폴리오 문서용):
- "archify로 CreditBook 배포 아키텍처 그려줘 (Vercel – Render – Neon, GitHub Actions)"
- "archify로 충전 API 요청 시퀀스 다이어그램 만들어줘 (ChargeController → ChargeService → PrepaidAccount → ledger_entries)"
- "archify로 ledger_entries 유형 흐름 그려줘 (CHARGE/USE와 반제 CHARGE_CANCEL/USE_CANCEL)"

---

## 에이전트 — 크고 독립적인 작업만 맡긴다

| 에이전트 | 역할 | 상태 |
|---|---|---|
| `backend-dev` | Spring Boot 도메인/API 구현, Flyway, 도메인 테스트. test-writer·code-reviewer가 생기기 전까지 그 역할도 겸함 (모델: opus) | 사용 중 |
| `test-writer` | JUnit5 + Playwright 작성·실행 | S2 추가 예정 (백엔드 셋업 후) |
| `frontend-dev` | React + Vite UI | S2 추가 예정 (백엔드 셋업 후) |
| `code-reviewer` | PR 리뷰, OWASP 보안 체크 (읽기 전용) | S3 추가 예정 |

- **맡길 만한 것**: "backend-dev 에이전트로 CB-8 도메인 로직과 테스트까지 통째로 해줘" — 범위가 분명하고 메인 대화를 어지럽히고 싶지 않은 작업
- **직접 하는 게 나은 것**: 작은 수정, 대화 맥락이 필요한 작업
- **이유**: 에이전트는 빈 상태에서 시작해 지금 대화의 결정 사항을 모른다. 필요한 맥락은 요청에 직접 적어 준다

새 에이전트 추가 절차는 CLAUDE.md "서브에이전트 추가 절차"를 따른다.

---
name: git-workflow
description: Git 워크플로 패턴 — 브랜치 전략, 커밋 컨벤션, merge와 rebase 선택, 충돌 해결, 협업 모범 사례. 브랜치 전략을 고르거나, 커밋 메시지 규칙을 정하거나, merge/rebase를 결정하거나, 충돌을 해결할 때 사용.
metadata:
  origin: ECC
---

# Git 워크플로 패턴

Git 버전 관리, 브랜치 전략, 협업 개발의 모범 사례.

> **CreditBook 규칙이 우선한다** (CLAUDE.md "Git / 커밋 규칙"):
> - **GitHub Flow만** 쓴다. `develop` 브랜치 없음. `main`은 항상 배포 가능하고 PR로만 병합한다
> - 브랜치: `feature/CB-42-charge-api`, `fix/CB-57-balance-boundary` — Jira 키(`CB-n`) 포함
> - 커밋: `<type>(<Jira 키>): <한 줄 요약>` — type은 feat/fix/test/refactor/docs/chore
> - 혼자 작업해도 PR을 거치고, CI가 실패한 상태로 병합하지 않는다
>
> 아래의 Trunk-Based, GitFlow, scope 예시(`auth`, `api` 등)는 일반 참고용이다.

## 언제 쓰는가

- 새 프로젝트의 Git 워크플로 설정
- 브랜치 전략 결정 (GitFlow, 트렁크 기반, GitHub Flow)
- 커밋 메시지와 PR 설명 작성
- 병합 충돌 해결
- 릴리스와 버전 태그 관리
- 새 팀원에게 Git 관례 안내

## 브랜치 전략

### GitHub Flow (단순, 대부분에 권장 — CreditBook 채택)

지속 배포를 하는 중소 규모 팀에 가장 잘 맞는다.

```
main (보호됨, 항상 배포 가능)
  │
  ├── feature/user-auth      → PR → main에 병합
  ├── feature/payment-flow   → PR → main에 병합
  └── fix/login-bug          → PR → main에 병합
```

**규칙:**
- `main`은 항상 배포 가능하다
- 기능 브랜치는 `main`에서 딴다
- 리뷰받을 준비가 되면 PR을 연다
- 승인과 CI 통과 후 `main`에 병합한다
- 병합 후 바로 배포한다

### 트렁크 기반 개발 (속도가 빠른 팀)

CI/CD와 기능 플래그가 탄탄한 팀에 맞는다.

```
main (trunk)
  │
  ├── 짧게 사는 기능 브랜치 (최대 1–2일)
  ├── 짧게 사는 기능 브랜치
  └── 짧게 사는 기능 브랜치
```

**규칙:**
- 모두가 `main` 또는 아주 짧게 사는 브랜치에 커밋한다
- 미완성 작업은 기능 플래그로 숨긴다
- 병합 전에 CI가 통과해야 한다
- 하루에 여러 번 배포한다

### GitFlow (복잡, 릴리스 주기 중심)

정해진 날짜에 릴리스하는 엔터프라이즈 프로젝트에 맞는다.

```
main (운영 릴리스)
  │
  └── develop (통합 브랜치)
        │
        ├── feature/user-auth
        ├── feature/payment
        │
        ├── release/1.0.0    → main과 develop에 병합
        │
        └── hotfix/critical  → main과 develop에 병합
```

**규칙:**
- `main`에는 운영 준비된 코드만 있다
- `develop`은 통합 브랜치다
- 기능 브랜치는 `develop`에서 따고 `develop`으로 병합한다
- 릴리스 브랜치는 `develop`에서 따고 `main`과 `develop`에 병합한다
- 핫픽스 브랜치는 `main`에서 따고 `main`과 `develop` 모두에 병합한다

### 언제 무엇을 쓰나

| 전략 | 팀 규모 | 릴리스 주기 | 적합한 곳 |
|------|---------|-------------|----------|
| GitHub Flow | 무관 | 지속 배포 | SaaS, 웹 앱, 스타트업 |
| 트렁크 기반 | 숙련자 5명 이상 | 하루 여러 번 | 빠른 팀, 기능 플래그 |
| GitFlow | 10명 이상 | 정기 | 엔터프라이즈, 규제 산업 |

## 커밋 메시지

### Conventional Commits 형식

```
<type>(<scope>): <subject>

[선택: 본문]

[선택: 꼬리말]
```

CreditBook에서는 `<scope>` 자리에 Jira 키를 쓴다: `feat(CB-42): 충전 API 추가`

### 타입

| 타입 | 용도 | 예시 |
|------|------|------|
| `feat` | 새 기능 | `feat(auth): add OAuth2 login` |
| `fix` | 버그 수정 | `fix(api): handle null response in user endpoint` |
| `docs` | 문서 | `docs(readme): update installation instructions` |
| `style` | 포맷만 변경, 코드 변화 없음 | `style: fix indentation in login component` |
| `refactor` | 리팩터링 | `refactor(db): extract connection pool to module` |
| `test` | 테스트 추가·수정 | `test(auth): add unit tests for token validation` |
| `chore` | 유지보수 작업 | `chore(deps): update dependencies` |
| `perf` | 성능 개선 | `perf(query): add index to users table` |
| `ci` | CI/CD 변경 | `ci: add PostgreSQL service to test workflow` |
| `revert` | 이전 커밋 되돌리기 | `revert: revert "feat(auth): add OAuth2 login"` |

CreditBook이 쓰는 타입은 feat/fix/test/refactor/docs/chore 여섯 가지다.

### 좋은 예와 나쁜 예

```
# 나쁨: 모호하고 맥락이 없음
git commit -m "fixed stuff"
git commit -m "updates"
git commit -m "WIP"

# 좋음: 명확하고 구체적이며 이유를 설명함
git commit -m "fix(CB-57): 잔액 경계값에서 사용이 거절되는 문제 수정

잔액과 사용 금액이 같을 때 InsufficientBalanceException이 발생했다.
비교를 > 에서 >= 로 바로잡고 경계값 테스트를 추가했다."
```

### 커밋 메시지 템플릿

리포 루트에 `.gitmessage`를 만든다:

```
# <type>(<scope>): <subject>
# 타입: feat, fix, docs, style, refactor, test, chore, perf, ci, revert
# scope: CreditBook은 Jira 키 (CB-42)
# subject: 한 줄 요약, 마침표 없음, 50자 이내
#
# [선택: 본문] - 무엇이 아니라 왜를 설명
# [선택: 꼬리말] - 호환성 깨짐, 관련 이슈
```

적용: `git config commit.template .gitmessage`

## Merge와 Rebase

### Merge (이력 보존)

```bash
# 병합 커밋을 만든다
git checkout main
git merge feature/user-auth

# 결과:
# *   병합 커밋
# |\
# | * 기능 브랜치 커밋
# |/
# * main 커밋
```

**이럴 때 쓴다:**
- 기능 브랜치를 `main`에 합칠 때
- 이력을 정확히 보존하고 싶을 때
- 여러 사람이 그 브랜치에서 작업했을 때
- 이미 push했고 다른 사람이 그 위에서 작업했을 수 있을 때

### Rebase (일직선 이력)

```bash
# 기능 브랜치 커밋을 대상 브랜치 위에 다시 쓴다
git checkout feature/user-auth
git rebase main

# 결과:
# * 기능 브랜치 커밋 (다시 쓰임)
# * main 커밋
```

**이럴 때 쓴다:**
- 로컬 기능 브랜치를 최신 `main`으로 갱신할 때
- 깔끔한 일직선 이력을 원할 때
- 브랜치가 로컬에만 있을 때 (push 전)
- 그 브랜치에서 나 혼자 작업할 때

### Rebase 절차

```bash
# PR 전에 기능 브랜치를 최신 main으로 갱신
git checkout feature/user-auth
git fetch origin
git rebase origin/main

# 충돌이 있으면 해결
# 테스트가 여전히 통과해야 한다

# 강제 push (나 혼자 쓰는 브랜치일 때만)
git push --force-with-lease origin feature/user-auth
```

### Rebase하면 안 되는 경우

```
# 다음 브랜치는 절대 rebase하지 않는다:
- 공유 저장소에 이미 push된 브랜치
- 다른 사람이 그 위에서 작업한 브랜치
- 보호 브랜치 (main, develop)
- 이미 병합된 브랜치

# 이유: rebase는 이력을 다시 써서 다른 사람의 작업을 깨뜨린다
```

## Pull Request 절차

### PR 제목 형식

```
<type>(<scope>): <description>

예시:
feat(CB-42): 충전 API 추가
fix(CB-57): 잔액 경계값 사용 거절 수정
docs(CB-20): API 명세 갱신
```

### PR 설명 템플릿

```markdown
## 무엇을

이 PR이 하는 일을 짧게 설명한다.

## 왜

동기와 맥락을 설명한다.

## 어떻게

짚고 넘어갈 구현 세부 사항.

## 테스트

- [ ] 단위 테스트 추가/수정
- [ ] 통합 테스트 추가/수정
- [ ] 수동 테스트 수행

## 스크린샷 (해당 시)

UI 변경의 전후 스크린샷.

## 체크리스트

- [ ] 프로젝트 스타일 가이드를 따른다
- [ ] 자가 리뷰를 마쳤다
- [ ] 복잡한 로직에 주석을 달았다
- [ ] 문서를 갱신했다
- [ ] 새 경고가 없다
- [ ] 로컬에서 테스트가 통과한다
- [ ] 관련 이슈(Jira 키)를 연결했다

Jira: CB-123
```

### 코드 리뷰 체크리스트

**리뷰어용:**

- [ ] 코드가 말한 문제를 해결하는가?
- [ ] 처리하지 않은 경계 상황이 있는가?
- [ ] 읽기 쉽고 유지보수하기 쉬운가?
- [ ] 테스트가 충분한가?
- [ ] 보안상 우려가 있는가?
- [ ] 커밋 이력이 깔끔한가 (필요하면 squash)?

**작성자용:**

- [ ] 리뷰 요청 전에 자가 리뷰를 마쳤다
- [ ] CI가 통과한다 (테스트, 린트, 타입 검사)
- [ ] PR 크기가 적당하다 (500줄 미만이 이상적)
- [ ] 기능·수정 하나에 관한 것이다
- [ ] 설명이 변경을 분명히 설명한다

## 충돌 해결

### 충돌 확인

```bash
# 병합 전에 충돌 여부 확인
git checkout main
git merge feature/user-auth --no-commit --no-ff

# 충돌이 있으면 Git이 이렇게 알려준다:
# CONFLICT (content): Merge conflict in src/auth/login.ts
# Automatic merge failed; fix conflicts and then commit the result.
```

### 충돌 해결

```bash
# 충돌 난 파일 보기
git status

# 파일 안의 충돌 표시
# <<<<<<< HEAD
# main 쪽 내용
# =======
# 기능 브랜치 쪽 내용
# >>>>>>> feature/user-auth

# 방법 1: 직접 해결
# 파일을 고치고, 표시를 지우고, 맞는 내용만 남긴다

# 방법 2: 병합 도구 사용
git mergetool

# 방법 3: 한쪽 채택
git checkout --ours src/auth/login.ts    # main 쪽 유지
git checkout --theirs src/auth/login.ts  # 기능 브랜치 쪽 유지

# 해결 후 스테이징하고 커밋
git add src/auth/login.ts
git commit
```

### 충돌 예방

```bash
# 1. 기능 브랜치를 작고 짧게 유지한다
# 2. main 위로 자주 rebase한다
git checkout feature/user-auth
git fetch origin
git rebase origin/main

# 3. 공유 파일을 건드릴 때는 팀과 미리 이야기한다
# 4. 오래 사는 브랜치 대신 기능 플래그를 쓴다
# 5. PR은 빨리 리뷰하고 병합한다
```

## 브랜치 관리

### 네이밍 규칙

```
# 기능 브랜치 (CreditBook: Jira 키 포함)
feature/CB-42-charge-api
feature/user-authentication

# 버그 수정
fix/CB-57-balance-boundary
fix/login-redirect-loop

# 핫픽스 (운영 장애)
hotfix/critical-security-patch
hotfix/database-connection-leak

# 릴리스
release/1.2.0

# 실험/POC
experiment/new-caching-strategy
poc/graphql-migration
```

### 브랜치 정리

```bash
# 병합된 로컬 브랜치 삭제
git branch --merged main | grep -v "^\*\|main" | xargs -n 1 git branch -d

# 원격에서 삭제된 브랜치의 추적 참조 정리
git fetch -p

# 로컬 브랜치 삭제
git branch -d feature/user-auth  # 안전 삭제 (병합된 경우만)
git branch -D feature/user-auth  # 강제 삭제

# 원격 브랜치 삭제
git push origin --delete feature/user-auth
```

### Stash 사용

```bash
# 진행 중인 작업 임시 저장
git stash push -m "WIP: user authentication"

# stash 목록
git stash list

# 가장 최근 stash 적용 후 제거
git stash pop

# 특정 stash 적용
git stash apply stash@{2}

# stash 삭제
git stash drop stash@{0}
```

## 릴리스 관리

### 시맨틱 버저닝

```
MAJOR.MINOR.PATCH

MAJOR: 호환이 깨지는 변경
MINOR: 새 기능, 하위 호환
PATCH: 버그 수정, 하위 호환

예시:
1.0.0 → 1.0.1 (patch: 버그 수정)
1.0.1 → 1.1.0 (minor: 새 기능)
1.1.0 → 2.0.0 (major: 호환 깨짐)
```

### 릴리스 만들기

```bash
# 주석 태그 생성
git tag -a v1.2.0 -m "Release v1.2.0

기능:
- 사용자 인증 추가
- 비밀번호 재설정 구현

수정:
- 로그인 리다이렉트 문제 해결

호환성 깨짐:
- 없음"

# 태그를 원격에 push
git push origin v1.2.0

# 태그 목록
git tag -l

# 태그 삭제
git tag -d v1.2.0
git push origin --delete v1.2.0
```

### 변경 이력(Changelog) 생성

```bash
# 커밋으로 변경 이력 만들기
git log v1.1.0..v1.2.0 --oneline --no-merges

# 또는 conventional-changelog 사용
npx conventional-changelog -i CHANGELOG.md -s
```

## Git 설정

### 필수 설정

```bash
# 사용자 정보
git config --global user.name "Your Name"
git config --global user.email "your@email.com"

# 기본 브랜치 이름
git config --global init.defaultBranch main

# pull 동작 (merge 대신 rebase)
git config --global pull.rebase true

# push 동작 (현재 브랜치만)
git config --global push.default current

# 오타 자동 교정
git config --global help.autocorrect 1

# 더 나은 diff 알고리즘
git config --global diff.algorithm histogram

# 색상 출력
git config --global color.ui auto
```

### 유용한 별칭

```bash
# ~/.gitconfig에 추가
[alias]
    co = checkout
    br = branch
    ci = commit
    st = status
    unstage = reset HEAD --
    last = log -1 HEAD
    visual = log --oneline --graph --all
    amend = commit --amend --no-edit
    wip = commit -m "WIP"
    undo = reset --soft HEAD~1
    contributors = shortlog -sn
```

### .gitignore 패턴

```gitignore
# 의존성
node_modules/
vendor/

# 빌드 산출물
dist/
build/
*.o
*.exe

# 환경 파일 (CreditBook: application-local.yml 포함)
.env
.env.local
.env.*.local
application-local.yml

# IDE
.idea/
.vscode/
*.swp
*.swo

# OS 파일
.DS_Store
Thumbs.db

# 로그
*.log
logs/

# 테스트 커버리지
coverage/

# 캐시
.cache/
*.tsbuildinfo
```

## 자주 쓰는 절차

### 새 기능 시작

```bash
# 1. main 갱신
git checkout main
git pull origin main

# 2. 기능 브랜치 생성
git checkout -b feature/CB-42-charge-api

# 3. 변경 후 커밋
git add .
git commit -m "feat(CB-42): 충전 API 추가"

# 4. 원격에 push
git push -u origin feature/CB-42-charge-api

# 5. GitHub에서 PR 생성
```

### PR에 변경 추가

```bash
# 1. 추가 변경
git add .
git commit -m "feat(CB-42): 오류 처리 추가"

# 2. push
git push origin feature/CB-42-charge-api
```

### 포크를 원본과 동기화

```bash
# 1. upstream 원격 추가 (한 번만)
git remote add upstream https://github.com/original/repo.git

# 2. upstream 가져오기
git fetch upstream

# 3. upstream/main을 내 main에 병합
git checkout main
git merge upstream/main

# 4. 내 포크에 push
git push origin main
```

### 실수 되돌리기

```bash
# 마지막 커밋 취소 (변경은 유지)
git reset --soft HEAD~1

# 마지막 커밋 취소 (변경도 버림)
git reset --hard HEAD~1

# 이미 원격에 push한 마지막 커밋 되돌리기
git revert HEAD
git push origin main

# 특정 파일 변경 되돌리기
git checkout HEAD -- path/to/file

# 마지막 커밋 메시지 수정
git commit --amend -m "New message"

# 마지막 커밋에 빠뜨린 파일 추가
git add forgotten-file
git commit --amend --no-edit
```

## Git 훅

### pre-commit 훅

```bash
#!/bin/bash
# .git/hooks/pre-commit

# 린트 실행
npm run lint || exit 1

# 테스트 실행
npm test || exit 1

# 시크릿 확인
if git diff --cached | grep -E '(password|api_key|secret)'; then
    echo "Possible secret detected. Commit aborted."
    exit 1
fi
```

### pre-push 훅

```bash
#!/bin/bash
# .git/hooks/pre-push

# 전체 테스트 실행
npm run test:all || exit 1

# console.log 확인
if git diff origin/main | grep -E 'console\.log'; then
    echo "Remove console.log statements before pushing."
    exit 1
fi
```

## 안티패턴

```
# 나쁨: main에 직접 커밋
git checkout main
git commit -m "fix bug"

# 좋음: 기능 브랜치와 PR 사용

# 나쁨: 시크릿 커밋
git add .env  # API 키가 들어 있음

# 좋음: .gitignore에 추가하고 환경변수 사용

# 나쁨: 거대한 PR (1000줄 이상)
# 좋음: 작고 집중된 PR로 나눈다

# 나쁨: "update" 같은 커밋 메시지
git commit -m "update"
git commit -m "fix"

# 좋음: 설명이 있는 메시지
git commit -m "fix(CB-60): 로그인 후 리다이렉트 무한 반복 해결"

# 나쁨: 공개된 이력 다시 쓰기
git push --force origin main

# 좋음: 공개 브랜치에는 revert 사용
git revert HEAD

# 나쁨: 오래 사는 기능 브랜치 (몇 주·몇 달)
# 좋음: 브랜치는 짧게(며칠), 자주 rebase

# 나쁨: 생성된 파일 커밋
git add dist/
git add node_modules/

# 좋음: .gitignore에 추가
```

## 빠른 참조

| 작업 | 명령어 |
|------|--------|
| 브랜치 생성 | `git checkout -b feature/name` |
| 브랜치 전환 | `git checkout branch-name` |
| 브랜치 삭제 | `git branch -d branch-name` |
| 브랜치 병합 | `git merge branch-name` |
| 브랜치 rebase | `git rebase main` |
| 이력 보기 | `git log --oneline --graph` |
| 변경 보기 | `git diff` |
| 스테이징 | `git add .` 또는 `git add -p` |
| 커밋 | `git commit -m "message"` |
| push | `git push origin branch-name` |
| pull | `git pull origin branch-name` |
| stash | `git stash push -m "message"` |
| 마지막 커밋 취소 | `git reset --soft HEAD~1` |
| 커밋 되돌리기 | `git revert HEAD` |

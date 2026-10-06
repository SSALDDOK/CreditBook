---
name: frontend-a11y
description: "React 접근성 패턴 — 시맨틱 HTML, 폼 라벨 연결, 오류 메시지 연결(aria-describedby·role=alert), ARIA, 키보드 조작, 모달 포커스. CreditBook에서는 접근성 기준이 Playwright의 getByRole·getByLabel 선택자와 그대로 이어지므로 E2E를 쓰기 쉬운 화면의 기준으로도 쓴다. 원본 ECC 스킬의 Next.js 언급·커스텀 드롭다운 구현은 shadcn/ui 사용 기준으로 바꿨다. forms, modals, keyboard navigation, focus management, aria를 다룰 때 사용."
metadata:
  origin: ECC (community — shadcn/ui·Playwright 기준으로 정리)
---

# 프론트엔드 접근성 (CreditBook)

매장 직원이 키보드와 터치로 빠르게 쓰는 화면이다. 접근성을 지키면 **E2E 테스트(사용자가 직접 작성)가 `getByRole`·`getByLabel`로 요소를 안정적으로 찾을 수 있다** — 접근성과 테스트 용이성이 같은 작업이다.

## 언제 쓰는가

- 입력 폼(로그인, 고객 등록, 충전·사용·취소 금액·사유)을 만들 때
- 모달(충전·사용·취소 확인)을 만들 때
- `div`·`span`에 `onClick`을 달고 싶어질 때
- 오류·안내 문구가 화면에 동적으로 나타날 때

## 폼

### 라벨 연결

```tsx
// 나쁨: 라벨과 입력이 연결되지 않아 화면낭독기·getByLabel 모두 찾지 못한다
<label>금액</label>
<input />

// 좋음
<label htmlFor="charge-amount">금액</label>
<input id="charge-amount" inputMode="numeric" data-testid="charge-amount" />
```

- `placeholder`를 라벨 대신 쓰지 않는다
- 금액 입력은 `inputMode="numeric"`(모바일 숫자 키패드). `type="number"`는 소수·지수 입력을 허용하므로 쓰지 않는다

### 필수·오류 표시

```tsx
<label htmlFor="cancel-reason">
  사유 <span aria-hidden="true">*</span>
</label>
<textarea
  id="cancel-reason"
  required
  aria-required="true"
  maxLength={200}
  aria-invalid={!!error}
  aria-describedby={error ? 'cancel-reason-error' : undefined}
/>
{error && (
  <p id="cancel-reason-error" role="alert" data-testid="cancel-reason-error">{error}</p>
)}
```

- 서버 `fieldErrors`도 같은 방식으로 해당 입력칸에 연결한다
- 폼 전체 오류(잔액 부족, 409 등)는 폼 위쪽 한 곳에 `role="alert"`로 보여 준다

## 시맨틱 HTML

- 누르는 것은 `<button type="button">`, 이동하는 것은 `<a>`(라우터 `Link`). `div onClick` 금지
- 페이지마다 `<h1>` 하나, 제목 단계는 건너뛰지 않는다
- 고객 목록은 `<table>`(행·열 의미가 있는 데이터) 또는 `<ul>`. 잔액 숫자는 `<td>` 안 텍스트로 둔다(`getByRole('cell')`로 찾을 수 있게)

## ARIA

- 네이티브 HTML로 안 될 때만 쓴다. 잘못된 ARIA는 없는 것보다 나쁘다
- 아이콘만 있는 버튼은 `aria-label`(예: `aria-label="검색어 지우기"`), 아이콘에는 `aria-hidden="true"`
- 충전·사용 성공 안내처럼 조용히 바뀌는 문구는 `role="status"`(`aria-live="polite"`), 실패는 `role="alert"`

## 키보드·포커스

- 모든 동작이 키보드만으로 된다. 양수 `tabIndex` 금지, 포커스 가능한 요소에 `aria-hidden` 금지
- **모달·드롭다운은 shadcn/ui(Radix) 컴포넌트를 쓴다** — 포커스 가두기·Esc 닫기·닫을 때 포커스 복원을 이미 처리한다. 직접 만들지 않는다
- 모달은 제목을 `DialogTitle`로 둔다(`getByRole('dialog', { name: '충전' })`로 찾을 수 있다)
- 로그인 성공·라우트 이동 뒤에는 새 화면의 `<h1>`이나 첫 입력칸으로 포커스를 옮긴다

## 금지 예

```tsx
<div onClick={submit}>확인</div>                 // button 이 아니다
<div role="button" onClick={submit}>확인</div>   // Enter/Space 처리·tabIndex 가 없다
<input placeholder="금액" />                      // 라벨이 없다
<button tabIndex={3}>저장</button>               // 탭 순서를 깨뜨린다
```

## 점검표

- [ ] 모든 입력에 `htmlFor`/`id`로 연결된 라벨
- [ ] 오류 문구는 `aria-describedby`로 입력칸에 연결하고 `role="alert"`
- [ ] `div`·`span`에 `onClick` 없음
- [ ] 아이콘 버튼에 `aria-label`
- [ ] 모달은 shadcn/ui Dialog, 제목 있음
- [ ] 키보드만으로 로그인 → 목록 → 로그아웃 가능

## 넣지 않은 것 (원본 대비)

- 커스텀 드롭다운·모달 직접 구현 예시 — shadcn/ui로 대신한다
- 모션 줄이기(`prefers-reduced-motion`) 훅 — 애니메이션을 쓰게 되면 그때 추가

## 관련

- `react-patterns` — 폼·오류 처리
- `e2e-testing` — 페이지 객체·대기 패턴(예시는 `data-testid` 선택자). 화면은 역할·라벨과 `data-testid`를 모두 갖춰 두고, 어떤 선택자를 쓸지는 테스트 작성자(사용자)가 정한다

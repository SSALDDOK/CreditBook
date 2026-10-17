/** '활성' 배지 — 같은 번호 확인 창에서 '비활성' 배지(InactiveBadge)와 나란히 쓴다. 표시만 한다. */
export function ActiveBadge({ testId = 'active-badge' }: { testId?: string }) {
  return (
    <span
      data-testid={testId}
      className="inline-flex shrink-0 items-center rounded-md bg-emerald-50 px-2 py-0.5 text-sm font-semibold text-emerald-700 ring-1 ring-emerald-200"
    >
      활성
    </span>
  )
}

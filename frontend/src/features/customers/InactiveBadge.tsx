/** '비활성' 배지 — 고객 목록 행과 상세 화면이 함께 쓴다. 표시만 하며 고객 상태를 바꾸지 않는다. */
export function InactiveBadge({ testId = 'inactive-badge' }: { testId?: string }) {
  return (
    <span
      data-testid={testId}
      className="inline-flex shrink-0 items-center rounded-md bg-muted px-2 py-0.5 text-sm font-semibold text-muted-foreground ring-1 ring-border"
    >
      비활성
    </span>
  )
}

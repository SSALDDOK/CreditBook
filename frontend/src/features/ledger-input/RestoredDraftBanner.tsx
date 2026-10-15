/** 재로그인 후 입력을 되살렸을 때 모달 위쪽 노란 안내(REQ-17). 문구는 모달마다 다르다(충전/사용) */
export function RestoredDraftBanner({ children, testId }: { children: string; testId: string }) {
  return (
    <p
      role="status"
      data-testid={testId}
      className="rounded-xl border border-amber-300 bg-amber-50 px-4 py-3 font-semibold text-amber-900"
    >
      {children}
    </p>
  )
}

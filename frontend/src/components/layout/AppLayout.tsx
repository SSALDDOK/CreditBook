import type { ReactNode } from 'react'
import { Sidebar } from './Sidebar'

/** title 은 문자열 또는 경로 표시(예: 고객 › 상세) */
type AppLayoutProps = { title: ReactNode; children: ReactNode }

/**
 * 화면 높이에 고정한다 — 내용이 길어도 사이드바(아래쪽 직원 표시 포함)는 늘어나지 않고 늘 보이며,
 * 넘치는 내용은 본문(main) 안에서만 스크롤된다. 기준 해상도는 태블릿 가로 1024×768(화면설계서 §0.1).
 */
export function AppLayout({ title, children }: AppLayoutProps) {
  return (
    <div className="flex h-dvh overflow-hidden bg-muted">
      <Sidebar />
      <div className="flex min-h-0 min-w-0 flex-1 flex-col">
        <header className="shrink-0 border-b bg-background px-10 py-4">
          <h1 className="text-2xl font-bold" data-testid="page-title">
            {title}
          </h1>
        </header>
        <main className="flex min-h-0 flex-1 flex-col overflow-y-auto px-10 py-4">{children}</main>
      </div>
    </div>
  )
}

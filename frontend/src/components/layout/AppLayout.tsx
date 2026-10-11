import type { ReactNode } from 'react'
import { Sidebar } from './Sidebar'

type AppLayoutProps = { title: string; children: ReactNode }

export function AppLayout({ title, children }: AppLayoutProps) {
  return (
    <div className="flex min-h-screen bg-muted">
      <Sidebar />
      <div className="flex min-w-0 flex-1 flex-col">
        <header className="border-b bg-background px-10 py-6">
          <h1 className="text-2xl font-bold" data-testid="page-title">
            {title}
          </h1>
        </header>
        <main className="flex flex-1 flex-col px-10 py-8">{children}</main>
      </div>
    </div>
  )
}

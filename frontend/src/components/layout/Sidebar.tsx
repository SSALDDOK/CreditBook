import { ArrowLeftRight, LayoutDashboard, Users } from 'lucide-react'
import type { ReactNode } from 'react'
import { cn } from '@/lib/utils'

// 매장명·직원 정보는 아직 목 표시다. 로그인 세션 연결은 로그인 화면 이슈에서 한다.
const STORE_NAME = '반달네 커피'
const MOCK_EMPLOYEE = { name: '김직원', roleLabel: '직원', role: 'STAFF' }

type NavItem = { label: string; href: string; icon: ReactNode; current?: boolean; testId: string }

// 대시보드·거래 이력 화면은 아직 없다 — 메뉴 링크 자리만 둔다.
const NAV_ITEMS: NavItem[] = [
  { label: '대시보드', href: '#dashboard', icon: <LayoutDashboard aria-hidden="true" />, testId: 'nav-dashboard' },
  { label: '고객', href: '/', icon: <Users aria-hidden="true" />, current: true, testId: 'nav-customers' },
  { label: '거래 이력', href: '#ledger', icon: <ArrowLeftRight aria-hidden="true" />, testId: 'nav-ledger' },
]

export function Sidebar() {
  return (
    <aside className="flex w-64 shrink-0 flex-col bg-sidebar px-4 py-8 text-sidebar-foreground">
      <div className="px-2">
        <p className="text-2xl font-bold text-white">CreditBook</p>
        <p className="mt-1 text-sm" data-testid="store-name">
          {STORE_NAME}
        </p>
      </div>

      <nav aria-label="주 메뉴" className="mt-10">
        <ul className="flex flex-col gap-2">
          {NAV_ITEMS.map((item) => (
            <li key={item.label}>
              <a
                href={item.href}
                aria-current={item.current ? 'page' : undefined}
                data-testid={item.testId}
                className={cn(
                  'flex items-center gap-3 rounded-xl px-4 py-3 text-base outline-none transition-colors [&_svg]:size-5',
                  'hover:bg-sidebar-accent/60 focus-visible:ring-3 focus-visible:ring-white/60',
                  item.current && 'bg-sidebar-accent font-semibold text-sidebar-accent-foreground',
                )}
              >
                {item.icon}
                {item.label}
              </a>
            </li>
          ))}
        </ul>
      </nav>

      <div className="mt-auto border-t border-sidebar-border px-2 pt-5">
        <p className="font-semibold text-white" data-testid="employee-name">
          {MOCK_EMPLOYEE.name}
        </p>
        <p className="mt-1 text-sm" data-testid="employee-role">
          {MOCK_EMPLOYEE.roleLabel} ({MOCK_EMPLOYEE.role})
        </p>
        <button
          type="button"
          disabled
          title="로그인 기능이 준비되면 사용할 수 있습니다"
          data-testid="logout-button"
          className="mt-4 text-sm disabled:cursor-not-allowed disabled:opacity-70"
        >
          로그아웃
        </button>
      </div>
    </aside>
  )
}

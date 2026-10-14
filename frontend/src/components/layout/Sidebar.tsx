import { ArrowLeftRight, LayoutDashboard, Users } from 'lucide-react'
import { useState, type ReactNode } from 'react'
import { NavLink } from 'react-router'
import type { Role } from '@/api/auth'
import { toUserMessage } from '@/api/client'
import { useAuth } from '@/features/auth/AuthContext'
import { cn } from '@/lib/utils'

// 매장명은 아직 목 표시다(매장 정보 API 없음). 직원 이름·역할은 로그인 세션 값이다.
const STORE_NAME = '반달네 커피'
const ROLE_LABELS: Record<Role, string> = { ADMIN: '사장', STAFF: '직원' }

type NavItem = { label: string; href: string; icon: ReactNode; testId: string }

// 대시보드·거래 이력 화면은 아직 없다 — 메뉴 링크 자리만 둔다.
const NAV_ITEMS: NavItem[] = [
  { label: '대시보드', href: '#dashboard', icon: <LayoutDashboard aria-hidden="true" />, testId: 'nav-dashboard' },
  { label: '고객', href: '/customers', icon: <Users aria-hidden="true" />, testId: 'nav-customers' },
  { label: '거래 이력', href: '#ledger', icon: <ArrowLeftRight aria-hidden="true" />, testId: 'nav-ledger' },
]

function navClass(current: boolean): string {
  return cn(
    'flex items-center gap-3 rounded-xl px-4 py-3 text-base outline-none transition-colors [&_svg]:size-5',
    'hover:bg-sidebar-accent/60 focus-visible:ring-3 focus-visible:ring-white/60',
    current && 'bg-sidebar-accent font-semibold text-sidebar-accent-foreground',
  )
}

export function Sidebar() {
  const { state, logout } = useAuth()
  const [loggingOut, setLoggingOut] = useState(false)
  const [logoutError, setLogoutError] = useState<string | null>(null)
  // 보호 화면 안에서만 그려지므로 항상 로그인 상태다
  const session = state.status === 'authenticated' ? state.session : null

  async function handleLogout() {
    setLoggingOut(true)
    setLogoutError(null)
    try {
      await logout()
      // 이동(로그인 화면으로 replace)은 보호 라우트가 한다
    } catch (e: unknown) {
      setLogoutError(toUserMessage(e))
      setLoggingOut(false)
    }
  }

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
              {item.href.startsWith('/') ? (
                // NavLink 가 현재 화면(하위 경로 포함 — 예: /customers/:id)이면 aria-current="page" 를 붙인다
                <NavLink to={item.href} data-testid={item.testId} className={({ isActive }) => navClass(isActive)}>
                  {item.icon}
                  {item.label}
                </NavLink>
              ) : (
                <a href={item.href} data-testid={item.testId} className={navClass(false)}>
                  {item.icon}
                  {item.label}
                </a>
              )}
            </li>
          ))}
        </ul>
      </nav>

      <div className="mt-auto border-t border-sidebar-border px-2 pt-5">
        <p className="font-semibold text-white" data-testid="employee-name">
          {session?.name}
        </p>
        <p className="mt-1 text-sm" data-testid="employee-role">
          {session && `${ROLE_LABELS[session.role]} (${session.role})`}
        </p>
        <button
          type="button"
          onClick={handleLogout}
          disabled={loggingOut}
          aria-busy={loggingOut}
          data-testid="logout-button"
          className="mt-4 rounded text-sm outline-none hover:text-white focus-visible:ring-3 focus-visible:ring-white/60 disabled:cursor-not-allowed disabled:opacity-70"
        >
          로그아웃
        </button>
        {logoutError && (
          <p role="alert" data-testid="logout-error" className="mt-2 text-sm text-red-300">
            {logoutError}
          </p>
        )}
      </div>
    </aside>
  )
}

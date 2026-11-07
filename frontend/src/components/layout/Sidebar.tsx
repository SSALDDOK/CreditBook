import { ArrowLeftRight, LayoutDashboard, UserCog, Users } from 'lucide-react'
import { useState, type ReactNode } from 'react'
import { Link, NavLink, useLocation } from 'react-router'
import type { Role } from '@/api/auth'
import { toUserMessage } from '@/api/client'
import { useAuth } from '@/features/auth/AuthContext'
import type { PasswordLocationState } from '@/features/auth/PasswordPage'
import { cn } from '@/lib/utils'
import { EMPLOYEES_PATH, PASSWORD_PATH } from '@/routes/redirect'

// 직원 이름·역할은 로그인 세션 값이다. 매장명은 받아 올 API가 없어 표시하지 않는다 — 고정 문구를 두면 개설한 매장과 다른 이름이 보인다
const ROLE_LABELS: Record<Role, string> = { ADMIN: '사장님', STAFF: '직원' }

type NavItem = { label: string; href: string; icon: ReactNode; testId: string }

// 대시보드·거래 이력 화면은 아직 없다 — 메뉴 링크 자리만 둔다.
const NAV_ITEMS: NavItem[] = [
  { label: '대시보드', href: '#dashboard', icon: <LayoutDashboard aria-hidden="true" />, testId: 'nav-dashboard' },
  { label: '고객', href: '/customers', icon: <Users aria-hidden="true" />, testId: 'nav-customers' },
  { label: '거래 이력', href: '#ledger', icon: <ArrowLeftRight aria-hidden="true" />, testId: 'nav-ledger' },
]

// 사장(ADMIN)에게만 보이는 메뉴. 숨기는 것은 편의다 — 직원이 주소로 오면 화면이 403 카드를 보이고, 서버도 403 으로 막는다
const ADMIN_NAV_ITEMS: NavItem[] = [
  { label: '직원 관리', href: EMPLOYEES_PATH, icon: <UserCog aria-hidden="true" />, testId: 'nav-employees' },
]

const FOOTER_BUTTON_CLASS =
  'rounded text-sm outline-none hover:text-white focus-visible:ring-3 focus-visible:ring-white/60 disabled:cursor-not-allowed disabled:opacity-70'

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
  const navItems = session?.role === 'ADMIN' ? [...NAV_ITEMS, ...ADMIN_NAV_ITEMS] : NAV_ITEMS
  const { pathname, search, hash } = useLocation()
  // 비밀번호 변경 화면에서 바꾸거나 [취소]하면 지금 화면으로 돌아온다 — 경로는 주소가 아니라 화면 이동 상태로 넘긴다
  const passwordState: PasswordLocationState = { from: pathname + search + hash }

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
      </div>

      <nav aria-label="주 메뉴" className="mt-10">
        <ul className="flex flex-col gap-2">
          {navItems.map((item) => (
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
        <div className="mt-4 flex flex-col items-start gap-2">
          <Link to={PASSWORD_PATH} state={passwordState} data-testid="change-password-link" className={FOOTER_BUTTON_CLASS}>
            비밀번호 변경
          </Link>
          <button
            type="button"
            onClick={handleLogout}
            disabled={loggingOut}
            aria-busy={loggingOut}
            data-testid="logout-button"
            className={FOOTER_BUTTON_CLASS}
          >
            로그아웃
          </button>
        </div>
        {logoutError && (
          <p role="alert" data-testid="logout-error" className="mt-2 text-sm text-red-300">
            {logoutError}
          </p>
        )}
      </div>
    </aside>
  )
}

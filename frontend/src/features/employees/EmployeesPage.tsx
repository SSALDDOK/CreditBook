import { Ellipsis } from 'lucide-react'
import { useRef, useState } from 'react'
import { useNavigate } from 'react-router'
import { ApiError, toUserMessage } from '@/api/client'
import {
  deactivateEmployee,
  resetEmployeePassword,
  type EmployeeListItem,
} from '@/api/employees'
import type { Role } from '@/api/auth'
import { Button } from '@/components/ui/button'
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from '@/components/ui/dropdown-menu'
import { useAuth } from '@/features/auth/AuthContext'
import { formatEntryTime } from '@/lib/format'
import { cn } from '@/lib/utils'
import { DEFAULT_AFTER_LOGIN } from '@/routes/redirect'
import { AddEmployeeDialog } from './AddEmployeeDialog'
import { ConfirmDialog, type ConfirmDialogContent } from './ConfirmDialog'
import { InitialPasswordDialog, type InitialPasswordNotice } from './InitialPasswordDialog'
import { useEmployeeList } from './useEmployeeList'

// SCR-12 직원 계정 관리 (REQ-30). 사장(ADMIN) 전용 — 사장이 아니면 API 를 부르지 않고 화면 안 403 카드만 보인다.
// 서버가 403 FORBIDDEN 을 줘도 같은 카드. 관리할 수 있는지(사장·본인 계정 보호)의 최종 판단은 서버가 한다 —
// 사장 행에 메뉴를 숨기는 것은 편의다(서버가 409 ADMIN_ACCOUNT_PROTECTED 로 막는다).
// 목록 순서는 서버 응답 그대로(사장 → 활성 → 비활성). 추가·초기화·비활성화 뒤에는 서버에서 다시 받는다.
// 모양(크기·색·글자)은 화면설계 이미지(12_default·12_rowmenu·12_staff403) 수치 그대로다.

export const EMPLOYEES_FORBIDDEN_MESSAGE = '이 기능은 사장님 계정에서만 사용할 수 있습니다'

const ROLE_LABELS: Record<Role, string> = { ADMIN: '사장님', STAFF: '직원' }

// 표 칸 모양 — 머리 36px(#F7F8FB·모서리 8), 행 49px(아래 구분선 #EEF0F4)
const HEAD_CELL = 'h-9 bg-[#F7F8FB] px-0 text-left align-middle text-[12px] font-bold text-[#6B7280]'
const BODY_CELL = 'h-[49px] border-b border-[#EEF0F4] px-0 align-middle'
/** 권한·상태 표시 — 높이 24·모서리 6·좌우 9px, 12px 굵게 */
const BADGE = 'inline-flex h-6 items-center rounded-[6px] px-[9px] text-[12px] font-bold'
/** 카드 — 모서리 14·테두리 #E3E5EC */
const CARD = 'rounded-[14px] border border-[#E3E5EC] bg-white'

type PendingAction = { kind: 'reset-password' | 'deactivate'; employee: EmployeeListItem }

function confirmContent(action: PendingAction): ConfirmDialogContent {
  const { name } = action.employee
  return action.kind === 'reset-password'
    ? {
        kind: action.kind,
        title: `${name} 직원의 비밀번호를 초기화할까요?`,
        description: '지금 비밀번호로는 로그인할 수 없게 됩니다.',
        confirmLabel: '초기화',
        pendingLabel: '비밀번호 초기화 중',
      }
    : {
        kind: action.kind,
        title: `${name} 직원을 비활성화할까요?`,
        description: '비활성화하면 이 아이디로 로그인할 수 없고 다시 활성화할 수 없습니다. 지난 거래 기록은 그대로 남습니다.',
        confirmLabel: '비활성화',
        pendingLabel: '비활성화 중',
        destructive: true,
      }
}

export function EmployeesPage() {
  const { state } = useAuth()
  const isAdmin = state.status === 'authenticated' && state.session.role === 'ADMIN'
  if (!isAdmin) return <EmployeesForbidden />
  return <EmployeesManager />
}

/** 사장이 아닐 때 — 내용 자리에 카드 하나(높이 220·가운데 정렬). [이전 화면으로]는 방문 기록이 없으면 고객 목록 */
function EmployeesForbidden() {
  const navigate = useNavigate()
  function goBack() {
    // react-router 가 방문 기록 상태에 남기는 순번(idx) — 0 이면 이 앱에서 처음 연 화면이다
    const idx = (window.history.state as { idx?: number } | null)?.idx ?? 0
    if (idx > 0) navigate(-1)
    else navigate(DEFAULT_AFTER_LOGIN, { replace: true })
  }
  return (
    <section
      aria-label="직원 계정"
      data-testid="employees-forbidden"
      className={cn(CARD, 'flex flex-col items-center px-7 pt-[66.5px] pb-[61px]')}
    >
      <p role="alert" className="text-center text-[17px] leading-[22px] font-bold text-[#141B34] break-keep">
        {EMPLOYEES_FORBIDDEN_MESSAGE}
      </p>
      <Button
        type="button"
        variant="outline"
        onClick={goBack}
        className="mt-[26.5px] h-[44px] w-[150px] rounded-[9px] border-[#DDE0E8] bg-white text-[15px] font-bold text-[#141B34]"
        data-testid="employees-forbidden-back"
      >
        이전 화면으로
      </Button>
    </section>
  )
}

function EmployeesManager() {
  const [reloadKey, setReloadKey] = useState(0)
  const reload = () => setReloadKey((k) => k + 1)
  const list = useEmployeeList(true, reloadKey)
  const [adding, setAdding] = useState(false)
  const [action, setAction] = useState<PendingAction | null>(null)
  // 초기 비밀번호 — 창이 열려 있는 동안만 메모리에 둔다. 닫으면 지운다
  const [notice, setNotice] = useState<InitialPasswordNotice | null>(null)
  // 창이 닫히면 포커스를 돌려줄 직원 — 그 행의 [⋯]. 없으면(추가·비활성화로 메뉴가 사라짐) [직원 추가]
  const focusEmployeeId = useRef<string | null>(null)
  const pageRef = useRef<HTMLElement>(null)

  function restoreFocus(e: Event) {
    const root = pageRef.current
    if (!root) return
    e.preventDefault()
    const id = focusEmployeeId.current
    const menu = id
      ? root.querySelector<HTMLElement>(`[data-testid="employee-row"][data-employee-id="${CSS.escape(id)}"] [data-testid="employee-row-menu"]`)
      : null
    ;(menu ?? root.querySelector<HTMLElement>('[data-testid="add-employee-button"]'))?.focus()
  }

  function openAction(next: PendingAction) {
    focusEmployeeId.current = next.employee.employeeId
    setAction(next)
  }

  if (list.status === 'error' && list.error instanceof ApiError && list.error.status === 403 && list.error.code === 'FORBIDDEN') {
    return <EmployeesForbidden />
  }

  const data = list.status === 'error' ? undefined : list.data
  const loading = list.status === 'loading'
  const existingLoginIds = data?.items.map((e) => e.loginId) ?? []

  async function confirmAction() {
    if (!action) return
    const { kind, employee } = action
    if (kind === 'reset-password') {
      const response = await resetEmployeePassword(employee.employeeId)
      setAction(null)
      setNotice({
        title: `${employee.name} 직원의 비밀번호를 초기화했습니다`,
        loginId: employee.loginId,
        initialPassword: response.initialPassword,
      })
    } else {
      await deactivateEmployee(employee.employeeId)
      // 비활성 행에는 메뉴가 없어진다 — 포커스는 [직원 추가]로
      focusEmployeeId.current = null
      setAction(null)
      reload()
    }
  }

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      {/* 카드 안쪽 여백 28 — 제목 18px·안내 12px, [+ 직원 추가] 120×40 */}
      <section
        ref={pageRef}
        aria-labelledby="employees-card-title"
        aria-busy={loading}
        data-testid="employees-page"
        data-state={loading ? 'loading' : list.status === 'error' ? 'error' : 'ready'}
        className={cn(CARD, 'flex min-h-0 flex-col overflow-hidden px-7 pt-[19px] pb-2')}
      >
        <div className="flex shrink-0 items-start justify-between gap-4">
          <div className="pt-[6px]">
            <h2 id="employees-card-title" className="text-[18px] leading-[22px] font-bold text-[#141B34]">
              직원 계정
            </h2>
            <p className="mt-[4.5px] text-[12px] leading-4 text-[#6B7280] break-keep" data-testid="employees-guide">
              직원 계정은 직원 권한으로만 만들어집니다. 사장님 계정은 매장 개설 때 만든 1개입니다.
            </p>
          </div>
          <Button
            type="button"
            onClick={() => {
              focusEmployeeId.current = null
              setAdding(true)
            }}
            disabled={!data}
            className="h-10 w-[120px] shrink-0 rounded-[9px] text-[14px] font-bold"
            data-testid="add-employee-button"
          >
            + 직원 추가
          </Button>
        </div>

        {list.status === 'error' ? (
          <div className="mt-6 flex flex-col items-start gap-3 pb-5">
            <p role="alert" data-testid="error-message" className="text-destructive">
              {toUserMessage(list.error)}
            </p>
            <Button type="button" variant="outline" onClick={reload} data-testid="employees-retry">
              다시 시도
            </Button>
          </div>
        ) : (
          <div className="mt-[23.5px] min-h-0 overflow-y-auto">
            <table className={cn('w-full table-fixed border-separate border-spacing-0', loading && 'opacity-60')}>
              <caption className="sr-only">직원 계정 — 이름, 아이디, 권한, 상태, 마지막 로그인</caption>
              {/* 열 너비는 이미지(표 714px)의 비율 — 이름 134 · 아이디 150 · 권한 110 · 상태 90 · 마지막 로그인 150 · 관리 80 */}
              <colgroup>
                <col className="w-[18.8%]" />
                <col className="w-[21%]" />
                <col className="w-[15.4%]" />
                <col className="w-[12.6%]" />
                <col className="w-[21%]" />
                <col className="w-[11.2%]" />
              </colgroup>
              <thead>
                <tr>
                  <th scope="col" className={cn(HEAD_CELL, 'rounded-l-[8px] pl-[14px]')}>
                    이름
                  </th>
                  <th scope="col" className={HEAD_CELL}>
                    아이디
                  </th>
                  <th scope="col" className={HEAD_CELL}>
                    권한
                  </th>
                  <th scope="col" className={HEAD_CELL}>
                    상태
                  </th>
                  <th scope="col" className={HEAD_CELL}>
                    마지막 로그인
                  </th>
                  <th scope="col" className={cn(HEAD_CELL, 'rounded-r-[8px]')}>
                    <span className="sr-only">관리</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {data?.items.map((employee) => (
                  <EmployeeRow
                    key={employee.employeeId}
                    employee={employee}
                    onResetPassword={() => openAction({ kind: 'reset-password', employee })}
                    onDeactivate={() => openAction({ kind: 'deactivate', employee })}
                  />
                ))}
                {!data && (
                  <tr>
                    <td colSpan={6} className="py-10 text-center text-[13px] text-[#6B7280]" data-testid="employees-loading">
                      불러오는 중…
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
        )}
      </section>

      <AddEmployeeDialog
        open={adding}
        existingLoginIds={existingLoginIds}
        onClose={() => setAdding(false)}
        onAdded={(response) => {
          setAdding(false)
          setNotice({
            title: `${response.name} 직원을 추가했습니다`,
            loginId: response.loginId,
            initialPassword: response.initialPassword,
          })
        }}
        onDuplicate={reload}
      />

      <ConfirmDialog
        content={action ? confirmContent(action) : null}
        onConfirm={confirmAction}
        onClose={() => setAction(null)}
        onRejected={reload}
        onCloseAutoFocus={restoreFocus}
      />

      <InitialPasswordDialog
        notice={notice}
        onClose={() => {
          setNotice(null)
          reload()
        }}
        onCloseAutoFocus={restoreFocus}
      />
    </div>
  )
}

function EmployeeRow({
  employee,
  onResetPassword,
  onDeactivate,
}: {
  employee: EmployeeListItem
  onResetPassword: () => void
  onDeactivate: () => void
}) {
  const isAdmin = employee.role === 'ADMIN'
  // 비활성 직원은 다시 활성화할 수 없고 초기화도 의미가 없어 메뉴를 두지 않는다
  const manageable = !isAdmin && employee.active
  // 비활성 직원은 이름·아이디·마지막 로그인을 회색으로(배경은 그대로)
  const muted = !employee.active
  return (
    <tr
      data-testid="employee-row"
      data-login-id={employee.loginId}
      data-employee-id={employee.employeeId}
      data-role={employee.role}
      data-active={employee.active}
    >
      <td
        className={cn(BODY_CELL, 'truncate pl-[14px] text-[14px] font-bold', muted ? 'text-[#9AA0AE]' : 'text-[#141B34]')}
        data-testid="employee-name-cell"
      >
        {employee.name}
      </td>
      <td
        className={cn(BODY_CELL, 'truncate text-[13.5px]', muted ? 'text-[#9AA0AE]' : 'text-[#141B34]')}
        data-testid="employee-login-id"
      >
        {employee.loginId}
      </td>
      <td className={BODY_CELL} data-testid="employee-role-cell">
        <span className={cn(BADGE, employee.role === 'ADMIN' ? 'bg-[#EAEEFC] text-[#2B4ACB]' : 'bg-[#F1F2F6] text-[#4B5263]')}>
          {ROLE_LABELS[employee.role]}
        </span>
      </td>
      <td className={BODY_CELL} data-testid="employee-status">
        <span className={cn(BADGE, employee.active ? 'bg-[#E7F6EE] text-[#1B7A4B]' : 'bg-[#F1F2F6] text-[#8A90A2]')}>
          {employee.active ? '활성' : '비활성'}
        </span>
      </td>
      <td
        className={cn(BODY_CELL, 'text-[13px] tabular-nums', muted ? 'text-[#9AA0AE]' : 'text-[#4B5263]')}
        data-testid="employee-last-login"
      >
        {employee.lastLoginAt ? formatEntryTime(employee.lastLoginAt) : '-'}
      </td>
      <td className={cn(BODY_CELL, 'text-center')}>
        {isAdmin ? (
          <span className="text-[12.5px] text-[#8A90A2]" data-testid="employee-self-badge">
            본인
          </span>
        ) : manageable ? (
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button
                type="button"
                variant="outline"
                aria-label={`${employee.name} 계정 관리`}
                className="h-[30px] w-9 rounded-[7px] border-[#DDE0E8] bg-white p-0 text-[#4B5263]"
                data-testid="employee-row-menu"
              >
                <Ellipsis aria-hidden="true" className="size-4" strokeWidth={2.5} />
              </Button>
            </DropdownMenuTrigger>
            {/* 행 메뉴 — 184px·모서리 10·테두리 #DDE0E8·그림자 없음, 항목 40px·글자 13.5, 사이 구분선 #EEF0F4 */}
            <DropdownMenuContent align="end" className="w-[184px] rounded-[10px] border border-[#DDE0E8] p-0 shadow-none ring-0">
              <DropdownMenuItem
                onSelect={onResetPassword}
                className="h-10 rounded-none border-b border-[#EEF0F4] px-4 text-[13.5px] text-[#141B34]"
                data-testid="employee-reset-password"
              >
                비밀번호 초기화
              </DropdownMenuItem>
              <DropdownMenuItem
                variant="destructive"
                onSelect={onDeactivate}
                className="h-10 rounded-none px-4 text-[13.5px] data-[variant=destructive]:text-[#C62828]"
                data-testid="employee-deactivate"
              >
                비활성화
              </DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
        ) : null}
      </td>
    </tr>
  )
}

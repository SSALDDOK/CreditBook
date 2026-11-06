import { lazy, Suspense } from 'react'
import { BrowserRouter, Navigate, Route, Routes } from 'react-router'
import { ErrorBoundary } from '@/components/ErrorBoundary'
import { AppLayout } from '@/components/layout/AppLayout'
import { AuthProvider } from '@/features/auth/AuthProvider'
import { LoginPage } from '@/features/auth/LoginPage'
import { PasswordPage } from '@/features/auth/PasswordPage'
import { SignupPage } from '@/features/auth/SignupPage'
import { CustomerDetailBreadcrumb, CustomerRegisterBreadcrumb } from '@/features/customers/CustomerDetailBreadcrumb'
import { CustomerDetailPage } from '@/features/customers/CustomerDetailPage'
import { CustomerListPage } from '@/features/customers/CustomerListPage'
import { CustomerRegisterPage } from '@/features/customers/CustomerRegisterPage'
import { CustomerTransactionsScreen } from '@/features/transactions/CustomerTransactionsPage'
import { RequireAuth } from '@/routes/RequireAuth'
import { DEFAULT_AFTER_LOGIN, EMPLOYEES_PATH, LOGIN_PATH, PASSWORD_PATH, SIGNUP_PATH } from '@/routes/redirect'

// 직원 관리는 사장만 쓰는 화면이라 처음 열 때 따로 불러온다 — 다른 화면의 첫 로딩에 메뉴 부품 코드를 싣지 않는다
const EmployeesPage = lazy(() =>
  import('@/features/employees/EmployeesPage').then((m) => ({ default: m.EmployeesPage })),
)

/**
 * 라우트 표 (react-router v7, declarative 모드)
 * - /login       SCR-1 로그인 (공개). 직원 계정이 하나도 없으면 /signup 으로 replace
 * - /signup      SCR-11 매장 개설·사장님 가입 (공개, 왼쪽 메뉴 없음). 계정이 있으면 /login 으로 replace
 * - /customers   SCR-3 고객 목록 (로그인 필요)
 * - /customers/new   SCR-4 고객 등록 (로그인 필요 — 검색어는 주소가 아니라 화면 이동 상태로 받는다)
 * - /customers/:customerId   SCR-5 고객 상세 (로그인 필요)
 * - /customers/:customerId/transactions   SCR-8 거래 이력 (로그인 필요 — 제목에 고객 이름이 들어가 레이아웃을 화면이 직접 그린다)
 * - /settings/employees   SCR-12 직원 계정 관리 (로그인 필요, 사장 전용 — 직원이면 화면 안 403 카드)
 * - /password    SCR-13 비밀번호 변경 (로그인 필요, 왼쪽 메뉴 없음). 비밀번호를 바꿔야 하는 세션은 다른 보호 화면 대신 여기로 온다
 * - 그 밖(/ 포함) → /customers 로 replace. 404 화면은 공통 오류 화면 이슈에서 만든다
 *
 * 로그인이 필요한 화면은 RequireAuth 레이아웃 라우트의 자식으로 둔다.
 * 다음 화면은 여기에 자식 Route 를 더하면 된다.
 */
export default function App() {
  return (
    <ErrorBoundary>
      <BrowserRouter>
        <AuthProvider>
          <Routes>
            <Route path={LOGIN_PATH} element={<LoginPage />} />
            <Route path={SIGNUP_PATH} element={<SignupPage />} />
            <Route element={<RequireAuth />}>
              <Route
                path="/customers"
                element={
                  <AppLayout title="고객">
                    <CustomerListPage />
                  </AppLayout>
                }
              />
              <Route
                path="/customers/new"
                element={
                  <AppLayout title={<CustomerRegisterBreadcrumb />}>
                    <CustomerRegisterPage />
                  </AppLayout>
                }
              />
              <Route
                path="/customers/:customerId"
                element={
                  <AppLayout title={<CustomerDetailBreadcrumb />}>
                    <CustomerDetailPage />
                  </AppLayout>
                }
              />
              <Route path="/customers/:customerId/transactions" element={<CustomerTransactionsScreen />} />
              <Route
                path={EMPLOYEES_PATH}
                element={
                  <AppLayout title="직원 관리">
                    <Suspense fallback={null}>
                      <EmployeesPage />
                    </Suspense>
                  </AppLayout>
                }
              />
              <Route path={PASSWORD_PATH} element={<PasswordPage />} />
            </Route>
            <Route path="*" element={<Navigate to={DEFAULT_AFTER_LOGIN} replace />} />
          </Routes>
        </AuthProvider>
      </BrowserRouter>
    </ErrorBoundary>
  )
}

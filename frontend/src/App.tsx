import { BrowserRouter, Navigate, Route, Routes } from 'react-router'
import { ErrorBoundary } from '@/components/ErrorBoundary'
import { AppLayout } from '@/components/layout/AppLayout'
import { AuthProvider } from '@/features/auth/AuthProvider'
import { LoginPage } from '@/features/auth/LoginPage'
import { CustomerDetailBreadcrumb, CustomerRegisterBreadcrumb } from '@/features/customers/CustomerDetailBreadcrumb'
import { CustomerDetailPage } from '@/features/customers/CustomerDetailPage'
import { CustomerListPage } from '@/features/customers/CustomerListPage'
import { CustomerRegisterPage } from '@/features/customers/CustomerRegisterPage'
import { CustomerTransactionsScreen } from '@/features/transactions/CustomerTransactionsPage'
import { RequireAuth } from '@/routes/RequireAuth'
import { DEFAULT_AFTER_LOGIN, LOGIN_PATH } from '@/routes/redirect'

/**
 * 라우트 표 (react-router v7, declarative 모드)
 * - /login       SCR-1 로그인 (공개)
 * - /customers   SCR-3 고객 목록 (로그인 필요)
 * - /customers/new   SCR-4 고객 등록 (로그인 필요 — 검색어는 주소가 아니라 화면 이동 상태로 받는다)
 * - /customers/:customerId   SCR-5 고객 상세 (로그인 필요)
 * - /customers/:customerId/transactions   SCR-8 거래 이력 (로그인 필요 — 제목에 고객 이름이 들어가 레이아웃을 화면이 직접 그린다)
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
            </Route>
            <Route path="*" element={<Navigate to={DEFAULT_AFTER_LOGIN} replace />} />
          </Routes>
        </AuthProvider>
      </BrowserRouter>
    </ErrorBoundary>
  )
}

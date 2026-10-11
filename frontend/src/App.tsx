import { ErrorBoundary } from '@/components/ErrorBoundary'
import { AppLayout } from '@/components/layout/AppLayout'
import { CustomerListPage } from '@/features/customers/CustomerListPage'

// 라우터는 로그인 화면·보호 라우트와 함께 들어온다. 지금은 고객 목록(SCR-3) 한 화면만 있다.
export default function App() {
  return (
    <ErrorBoundary>
      <AppLayout title="고객">
        <CustomerListPage />
      </AppLayout>
    </ErrorBoundary>
  )
}

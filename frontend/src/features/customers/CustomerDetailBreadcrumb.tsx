import { Link, useLocation } from 'react-router'

type DetailLocationState = { listSearch?: string } | null

/**
 * 고객 상세의 제목 '고객 › 상세'. '고객'을 누르면 고객 목록으로 돌아간다.
 * 목록에서 들어왔으면 그때의 검색어·페이지·비활성 포함(목록 주소의 ?q=&page=&inactive=)을 그대로 되살리고,
 * 주소를 직접 열었으면 처음 목록으로 간다.
 */
export function CustomerDetailBreadcrumb() {
  const location = useLocation()
  const listSearch = (location.state as DetailLocationState)?.listSearch ?? ''

  return (
    <>
      <Link
        to={`/customers${listSearch}`}
        data-testid="breadcrumb-customers"
        className="rounded text-muted-foreground outline-none hover:text-foreground hover:underline focus-visible:ring-3 focus-visible:ring-ring/50"
      >
        고객
      </Link>{' '}
      <span aria-hidden="true">›</span> <span aria-current="page">상세</span>
    </>
  )
}

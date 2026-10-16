import type { ReactNode } from 'react'
import { Link, useLocation } from 'react-router'

export type CustomerLocationState = { listSearch?: string } | null

const LINK_CLASS =
  'rounded text-muted-foreground outline-none hover:text-foreground hover:underline focus-visible:ring-3 focus-visible:ring-ring/50'

/**
 * 고객 화면의 경로 표시. 맨 앞 '고객'을 누르면 고객 목록으로 돌아간다.
 * 목록에서 들어왔으면 그때의 검색어·페이지·비활성 포함(목록 주소의 ?q=&page=&inactive=)을 그대로 되살리고,
 * 주소를 직접 열었으면 처음 목록으로 간다. 그 상태(listSearch)는 상세 → 거래 이력으로 갈 때도 함께 넘긴다.
 *
 * - 고객 상세(SCR-5):  고객 › 상세
 * - 거래 이력(SCR-8):  고객 › (이름, 상세로 가는 링크) › 거래 이력
 */
export function CustomerBreadcrumb({
  customer,
  current,
}: {
  /** 중간 단계로 보일 고객. 이름을 아직 받지 못했으면 name 은 null */
  customer?: { id: string; name: string | null }
  current: ReactNode
}) {
  const location = useLocation()
  const listSearch = (location.state as CustomerLocationState)?.listSearch ?? ''

  return (
    <>
      <Link to={`/customers${listSearch}`} data-testid="breadcrumb-customers" className={LINK_CLASS}>
        고객
      </Link>{' '}
      <span aria-hidden="true">›</span>{' '}
      {customer && (
        <>
          <Link
            to={`/customers/${customer.id}`}
            state={location.state}
            data-testid="breadcrumb-customer"
            className={LINK_CLASS}
          >
            {customer.name ?? '…'}
          </Link>{' '}
          <span aria-hidden="true">›</span>{' '}
        </>
      )}
      <span aria-current="page">{current}</span>
    </>
  )
}

/** 고객 상세의 제목 '고객 › 상세' */
export function CustomerDetailBreadcrumb() {
  return <CustomerBreadcrumb current="상세" />
}

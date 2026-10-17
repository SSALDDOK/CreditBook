import type { ReactNode } from 'react'
import { Link, useLocation } from 'react-router'
import { customerListTarget, listReturnOf } from './customerListReturn'

// 상위 단계 링크는 주 버튼과 같은 파란색(primary 토큰). 지금 화면(aria-current)은 본문 색
const LINK_CLASS =
  'rounded text-primary outline-none hover:underline focus-visible:ring-3 focus-visible:ring-primary/40'

/**
 * 고객 화면의 경로 표시. 맨 앞 '고객'을 누르면 고객 목록으로 돌아간다.
 * 목록에서 들어왔으면 그때의 검색어·페이지·비활성 포함을 그대로 되살리고(화면 이동 상태 listReturn — 검색어는 주소에 없다),
 * 주소를 직접 열었으면 처음 목록으로 간다. 그 상태는 상세 → 거래 이력으로 갈 때도 함께 넘긴다.
 *
 * - 고객 등록(SCR-4):  고객 › 등록
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
  const list = customerListTarget(listReturnOf(location.state))

  return (
    <>
      <Link to={list.to} state={list.state} data-testid="breadcrumb-customers" className={LINK_CLASS}>
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

/** 고객 등록의 제목 '고객 › 등록' */
export function CustomerRegisterBreadcrumb() {
  return <CustomerBreadcrumb current="등록" />
}

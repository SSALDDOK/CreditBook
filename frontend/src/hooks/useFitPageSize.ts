import { useEffect, useRef, useState, type RefObject } from 'react'

type FitOptions = {
  /** 표 머리글 줄 높이(px) — 실제 머리글을 재지 못했을 때만 쓴다 */
  headerHeight: number
  /** 데이터 한 줄의 최소 높이(px) */
  minRowHeight: number
  min: number
  max: number
  /**
   * 잰 영역 안에서 행이 쓸 수 없는 높이(px, 기본 0) — 표를 감싼 카드의 위아래 테두리처럼,
   * 잰 요소(ref) 안쪽에 있지만 머리글·행이 아닌 부분
   */
  reservedHeight?: number
}

export type FitPageSize = { size: number; rowHeight: number }

/**
 * 표 영역(ref)의 높이에 스크롤 없이 들어가는 행 수와, 남는 자투리를 행마다 나눠 준 행 높이를 돌려준다.
 * 창 크기가 바뀌면 다시 계산한다. 기기마다 화면 높이가 달라(매장 태블릿 1024×768, PC 등)
 * 고정 행 수로는 표 아래에 빈 공간이 남거나 넘치기 때문이다.
 * 아직 재지 못했으면 null — 그동안은 조회하지 않는다(잘못된 크기로 한 번 더 부르지 않게).
 * 고객 목록(SCR-3)과 거래 이력(SCR-8)이 함께 쓴다.
 */
export function useFitPageSize(ref: RefObject<HTMLElement | null>, options: FitOptions): FitPageSize | null {
  const { headerHeight, minRowHeight, min, max, reservedHeight = 0 } = options
  const [fit, setFit] = useState<FitPageSize | null>(null)

  useEffect(() => {
    const element = ref.current
    if (!element) return
    const observer = new ResizeObserver(([entry]) => {
      const header = element.querySelector('thead')?.getBoundingClientRect().height || headerHeight
      const available = entry.contentRect.height - header - reservedHeight
      const size = Math.min(max, Math.max(min, Math.floor(available / minRowHeight)))
      const rowHeight = Math.max(minRowHeight, Math.floor(available / size))
      setFit((prev) => (prev?.size === size && prev.rowHeight === rowHeight ? prev : { size, rowHeight }))
    })
    observer.observe(element)
    return () => observer.disconnect()
  }, [ref, headerHeight, minRowHeight, min, max, reservedHeight])

  return fit
}

/**
 * 창 크기가 바뀌어 한 페이지 행 수가 달라지면, 보던 페이지의 첫 행이 들어 있는 페이지로 옮긴다.
 * (행 번호 계산일 뿐 금액과 관계없다.) 첫 페이지면 옮길 필요가 없다.
 */
export function useKeepFirstRowOnResize(pageSize: number | null, page: number, setPage: (page: number) => void) {
  const prevPageSize = useRef<number | null>(null)
  useEffect(() => {
    const prev = prevPageSize.current
    prevPageSize.current = pageSize
    if (prev === null || pageSize === null || prev === pageSize || page === 0) return
    const next = Math.floor((page * prev) / pageSize)
    if (next !== page) setPage(next)
    // eslint-disable-next-line react-hooks/exhaustive-deps -- 행 수가 바뀔 때만 옮긴다
  }, [pageSize])
}

import { useEffect, useState, type RefObject } from 'react'

type FitOptions = {
  /** 표 머리글 줄 높이(px) — 실제 머리글을 재지 못했을 때만 쓴다 */
  headerHeight: number
  /** 데이터 한 줄의 최소 높이(px) */
  minRowHeight: number
  min: number
  max: number
}

export type FitPageSize = { size: number; rowHeight: number }

/**
 * 표 영역(ref)의 높이에 스크롤 없이 들어가는 행 수와, 남는 자투리를 행마다 나눠 준 행 높이를 돌려준다.
 * 창 크기가 바뀌면 다시 계산한다. 기기마다 화면 높이가 달라(매장 태블릿 1024×768, PC 등)
 * 고정 행 수로는 표 아래에 빈 공간이 남거나 넘치기 때문이다.
 * 아직 재지 못했으면 null — 그동안은 조회하지 않는다(잘못된 크기로 한 번 더 부르지 않게).
 */
export function useFitPageSize(ref: RefObject<HTMLElement | null>, options: FitOptions): FitPageSize | null {
  const { headerHeight, minRowHeight, min, max } = options
  const [fit, setFit] = useState<FitPageSize | null>(null)

  useEffect(() => {
    const element = ref.current
    if (!element) return
    const observer = new ResizeObserver(([entry]) => {
      const header = element.querySelector('thead')?.getBoundingClientRect().height || headerHeight
      const available = entry.contentRect.height - header
      const size = Math.min(max, Math.max(min, Math.floor(available / minRowHeight)))
      const rowHeight = Math.max(minRowHeight, Math.floor(available / size))
      setFit((prev) => (prev?.size === size && prev.rowHeight === rowHeight ? prev : { size, rowHeight }))
    })
    observer.observe(element)
    return () => observer.disconnect()
  }, [ref, headerHeight, minRowHeight, min, max])

  return fit
}

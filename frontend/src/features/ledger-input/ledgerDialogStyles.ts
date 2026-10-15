import { cn } from '@/lib/utils'

// 충전·사용 모달 아래 버튼 클래스 — 컴포넌트 파일과 분리해 둔다(빠른 새로고침이 컴포넌트만 내보내는 파일에서만 동작한다).

/** 모달 아래 버튼 공통 크기(터치 영역 44px 이상) */
export const LEDGER_DIALOG_BUTTON_CLASS = 'h-12 min-w-28 rounded-xl px-6 text-lg font-bold'

/**
 * 주 버튼([충전]·[사용]·[확인]) — 공통 크기 + 비활성일 때 회색 바탕·회색 글자(화면설계).
 * 기본 버튼의 비활성 표시(흐린 파랑, opacity-50)는 "눌러도 되는 버튼"처럼 보여서 바꾼다.
 */
export const LEDGER_DIALOG_PRIMARY_BUTTON_CLASS = cn(
  LEDGER_DIALOG_BUTTON_CLASS,
  'disabled:bg-zinc-200 disabled:text-zinc-500 disabled:opacity-100',
)

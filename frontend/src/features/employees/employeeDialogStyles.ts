// 직원 관리(SCR-12) 창 공통 모양 — 화면설계 이미지 수치 그대로(1024×768 기준 창 460px·모서리 18px·안쪽 여백 30px).

export const DIALOG_CONTENT_CLASS =
  'max-h-[calc(100dvh-2rem)] gap-0 overflow-y-auto rounded-[18px] bg-white p-0 px-[30px] pt-[30px] pb-[19px] text-base ring-0 sm:max-w-[460px]'
/** 뒷배경 — #141B34 55% (흐림 없음) */
export const DIALOG_OVERLAY_CLASS = 'bg-[#141B34]/55 supports-backdrop-filter:backdrop-blur-none'
export const DIALOG_TITLE_CLASS = 'text-[19px] leading-[23px] font-bold text-[#141B34] break-keep'
export const DIALOG_DESCRIPTION_CLASS = 'mt-[9px] text-[12.5px] leading-[17px] text-[#6B7280] break-keep'
/** 아래 버튼 줄 — 오른쪽 정렬, 간격 10px */
export const DIALOG_FOOTER_CLASS = 'mt-5 flex justify-end gap-[10px]'
const DIALOG_BUTTON_BASE = 'h-[44px] rounded-[9px] px-4 text-[15px] font-bold'
/** [취소] — 흰 바탕 회색 테두리, 110px */
export const DIALOG_CANCEL_BUTTON_CLASS = `${DIALOG_BUTTON_BASE} min-w-[110px] border-[#DDE0E8] bg-white text-[#141B34]`
/** 주 버튼 — 130px */
export const DIALOG_PRIMARY_BUTTON_CLASS = `${DIALOG_BUTTON_BASE} min-w-[130px]`

// 계정 입력 화면(SCR-12 직원 추가·SCR-13 비밀번호 변경) 공통 입력 칸 모양 — 화면설계 이미지 수치 그대로.
// 포커스는 공통 입력칸(components/ui/input.tsx)의 파란(primary) 2px 테두리를 그대로 쓰고, 오류는 빨간 2px 테두리(번짐 없음).

/** 칸 이름 줄(label + '필수'를 감싸는 div): 13px 굵게. '필수'는 label 밖 REQUIRED_MARK_CLASS (aria-hidden — 접근 이름은 칸 이름만, 필수는 aria-required) */
export const FIELD_LABEL_CLASS = 'flex items-center gap-[5px] text-[13px] leading-[18px] font-bold text-[#141B34]'
export const REQUIRED_MARK_CLASS = 'text-[11px] font-normal text-[#C62828]'

export const FIELD_INPUT_CLASS =
  'mt-[6.5px] h-[42px] rounded-[9px] border-[#DDE0E8] bg-white px-[15px] text-[14px] text-[#141B34] md:text-[14px] ' +
  'aria-invalid:border-[#D32F2F] aria-invalid:ring-1 aria-invalid:ring-[#D32F2F] ' +
  'aria-invalid:focus-visible:border-[#D32F2F] aria-invalid:focus-visible:ring-1 aria-invalid:focus-visible:ring-[#D32F2F]'

/** 칸 아래 도움말·오류 한 줄: 11.5px */
export const FIELD_HELP_CLASS = 'mt-[5px] text-[11.5px] leading-[14px] text-[#6B7280]'
export const FIELD_HELP_OK_CLASS = 'text-[#1B7A4B]'
export const FIELD_ERROR_CLASS = 'mt-[5px] text-[11.5px] leading-[14px] text-[#C62828]'

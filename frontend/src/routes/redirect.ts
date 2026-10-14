// 로그인 화면으로 보낼 때 "원래 보던 화면"을 주소(?next=)에 담고, 로그인 뒤 그 화면으로 돌려보낸다.
// next 는 주소창에서 누구나 바꿀 수 있으므로 같은 출처의 앱 내부 경로만 받는다(오픈 리다이렉트 방지).

export const LOGIN_PATH = '/login'
export const DEFAULT_AFTER_LOGIN = '/customers'

/** 로그인 화면으로 오게 된 이유. expired 면 "로그인이 만료되었습니다" 안내를 띄운다. */
export type LoginReason = 'expired'

// 경로에 제어 문자·역슬래시가 있으면 브라우저마다 해석이 달라질 수 있어 받지 않는다
function hasUnsafeChars(value: string): boolean {
  for (const ch of value) {
    const code = ch.charCodeAt(0)
    if (code <= 0x1f || code === 0x7f || ch === '\\') return true
  }
  return false
}

/**
 * next 값을 검사해 안전한 앱 내부 경로(pathname+search+hash)를 돌려준다. 안전하지 않으면 기본 화면(/customers).
 * - '/'로 시작해야 한다. '//evil.com'(프로토콜 상대 주소), 'http://…', 'javascript:…'는 거절
 * - URL 로 풀어 본 출처가 지금 출처와 같아야 한다
 * - 로그인 화면 자신으로는 돌려보내지 않는다(무한 이동 방지)
 */
export function safeNextPath(next: string | null | undefined): string {
  if (!next || !next.startsWith('/') || next.startsWith('//') || hasUnsafeChars(next)) {
    return DEFAULT_AFTER_LOGIN
  }
  let url: URL
  try {
    url = new URL(next, window.location.origin)
  } catch {
    return DEFAULT_AFTER_LOGIN
  }
  if (url.origin !== window.location.origin) return DEFAULT_AFTER_LOGIN
  if (url.pathname === LOGIN_PATH || url.pathname.startsWith(`${LOGIN_PATH}/`)) return DEFAULT_AFTER_LOGIN
  return url.pathname + url.search + url.hash
}

/** 로그인 화면 주소. next 가 기본 화면이면 생략해 주소를 짧게 둔다. */
export function loginPath({ next, reason }: { next?: string; reason?: LoginReason } = {}): string {
  const params = new URLSearchParams()
  if (next) {
    const safe = safeNextPath(next)
    if (safe !== DEFAULT_AFTER_LOGIN) params.set('next', safe)
  }
  if (reason) params.set('reason', reason)
  const query = params.toString()
  return query ? `${LOGIN_PATH}?${query}` : LOGIN_PATH
}

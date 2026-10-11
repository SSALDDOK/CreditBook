/// <reference types="vite/client" />

// VITE_ 로 시작하는 값은 브라우저 번들에 그대로 들어간다. 공개해도 되는 값만 둔다.
interface ImportMetaEnv {
  readonly VITE_API_BASE_URL?: string
  readonly VITE_USE_MOCK?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}

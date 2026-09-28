---
name: cb-security-checklist
description: CreditBook 프로젝트용 범용 보안 체크리스트(시크릿 관리, 인증/인가, 입력 검증, API 보안). Claude Code 내장 security-review 스킬과 이름이 겹치지 않도록 별도 이름을 쓴다 — 내장 스킬(브랜치 전체 보안 리뷰 실행)을 대체하지 않고, 구현 중 참고할 패턴 레퍼런스로 보완한다. 인증, 사용자 입력 처리, 시크릿 취급, API 엔드포인트, 결제/민감정보 기능을 다룰 때 사용.
metadata:
  origin: ECC
---

# 보안 체크리스트

모든 코드가 보안 모범 사례를 따르도록 하고 잠재적 취약점을 찾아낸다.

> CreditBook 주의: 아래 예시 코드는 원본(ECC)의 TypeScript/Next.js/Supabase 예시다. 원칙만 가져가고, 구현은 Spring Boot 방식(`springboot-security` skill)으로 한다. Supabase RLS와 블록체인 항목은 이 프로젝트에 해당하지 않는다.

## 언제 쓰는가

- 인증이나 인가 구현
- 사용자 입력이나 파일 업로드 처리
- 새 API 엔드포인트 생성
- 시크릿이나 자격증명 취급
- 결제·금액 관련 기능 구현
- 민감정보 저장·전송
- 외부 API 연동

## 보안 체크리스트

### 1. 시크릿 관리

#### 나쁨: 절대 하지 말 것
```typescript
const apiKey = "sk-proj-xxxxx"  // 하드코딩된 시크릿
const dbPassword = "password123" // 소스 코드 안에
```

#### 좋음: 항상 이렇게
```typescript
const apiKey = process.env.OPENAI_API_KEY
const dbUrl = process.env.DATABASE_URL

// 시크릿이 있는지 확인
if (!apiKey) {
  throw new Error('OPENAI_API_KEY not configured')
}
```

#### 확인 항목
- [ ] 하드코딩된 API 키, 토큰, 비밀번호가 없다
- [ ] 모든 시크릿이 환경변수에 있다
- [ ] `.env`, `application-local.yml`이 .gitignore에 있다
- [ ] git 이력에 시크릿이 없다
- [ ] 운영 시크릿은 호스팅 플랫폼(Vercel, Render)에 있다

### 2. 입력 검증

#### 사용자 입력은 항상 검증한다
```typescript
import { z } from 'zod'

// 검증 스키마 정의
const CreateUserSchema = z.object({
  email: z.string().email(),
  name: z.string().min(1).max(100),
  age: z.number().int().min(0).max(150)
})

// 처리 전에 검증
export async function createUser(input: unknown) {
  try {
    const validated = CreateUserSchema.parse(input)
    return await db.users.create(validated)
  } catch (error) {
    if (error instanceof z.ZodError) {
      return { success: false, errors: error.issues }
    }
    throw error
  }
}
```

#### 파일 업로드 검증
```typescript
function validateFileUpload(file: File) {
  // 크기 확인 (최대 5MB)
  const maxSize = 5 * 1024 * 1024
  if (file.size > maxSize) {
    throw new Error('File too large (max 5MB)')
  }

  // 타입 확인
  const allowedTypes = ['image/jpeg', 'image/png', 'image/gif']
  if (!allowedTypes.includes(file.type)) {
    throw new Error('Invalid file type')
  }

  // 확장자 확인
  const allowedExtensions = ['.jpg', '.jpeg', '.png', '.gif']
  const extension = file.name.toLowerCase().match(/\.[^.]+$/)?.[0]
  if (!extension || !allowedExtensions.includes(extension)) {
    throw new Error('Invalid file extension')
  }

  return true
}
```

#### 확인 항목
- [ ] 모든 사용자 입력을 스키마(Bean Validation)로 검증한다
- [ ] 파일 업로드를 제한한다 (크기, 타입, 확장자)
- [ ] 사용자 입력을 쿼리에 직접 넣지 않는다
- [ ] 차단 목록이 아니라 허용 목록 방식으로 검증한다
- [ ] 오류 메시지에 민감정보가 새지 않는다

### 3. SQL 인젝션 방지

#### 나쁨: SQL을 문자열로 이어 붙이지 않는다
```typescript
// 위험 — SQL 인젝션 취약점
const query = `SELECT * FROM users WHERE email = '${userEmail}'`
await db.query(query)
```

#### 좋음: 항상 파라미터 바인딩 쿼리를 쓴다
```typescript
// 안전 — 파라미터 바인딩 쿼리
const { data } = await supabase
  .from('users')
  .select('*')
  .eq('email', userEmail)

// 또는 원시 SQL — 값은 문자열이 아니라 파라미터 배열에 넣는다.
// 드라이버의 자리표시자 문법을 쓴다 (Postgres는 번호를 매긴 자리표시자, MySQL은 "?").
await db.query(
  'SELECT * FROM users WHERE email = ?',
  [userEmail]
)
```

<!-- 이 파일 어디에도 '달러 기호 + 숫자' 형태의 자리표시자를 그대로 쓰지 않는다.
     이 skill을 인자와 함께 호출하면 그 부분이 치환돼 위 예시가 문자열 연결 SQL처럼
     보이게 된다 — 이 절이 경고하는 바로 그 안티패턴이다. "?"를 쓰고 Postgres 형식은 글로 설명한다. -->

#### 확인 항목
- [ ] 모든 DB 쿼리가 파라미터 바인딩을 쓴다
- [ ] SQL에 문자열 연결이 없다
- [ ] ORM/쿼리 빌더(JPA, Spring Data)를 올바르게 쓴다
- [ ] 네이티브 쿼리는 `:param` 바인딩을 쓴다

### 4. 인증과 인가

#### JWT 토큰 처리
```typescript
// 나쁨: localStorage (XSS에 취약)
localStorage.setItem('token', token)

// 좋음: httpOnly 쿠키
res.setHeader('Set-Cookie',
  `token=${token}; HttpOnly; Secure; SameSite=Strict; Max-Age=3600`)
```

#### 인가 확인
```typescript
export async function deleteUser(userId: string, requesterId: string) {
  // 항상 권한부터 확인한다
  const requester = await db.users.findUnique({
    where: { id: requesterId }
  })

  if (requester.role !== 'admin') {
    return NextResponse.json(
      { error: 'Unauthorized' },
      { status: 403 }
    )
  }

  // 삭제 진행
  await db.users.delete({ where: { id: userId } })
}
```

#### 행 수준 보안 (Supabase, CreditBook 해당 없음)
```sql
-- 모든 테이블에 RLS 활성화
ALTER TABLE users ENABLE ROW LEVEL SECURITY;

-- 사용자는 자기 데이터만 조회
CREATE POLICY "Users view own data"
  ON users FOR SELECT
  USING (auth.uid() = id);

-- 사용자는 자기 데이터만 수정
CREATE POLICY "Users update own data"
  ON users FOR UPDATE
  USING (auth.uid() = id);
```

#### 확인 항목
- [ ] 토큰을 localStorage가 아닌 httpOnly 쿠키에 저장한다
- [ ] 민감한 작업 전에 권한을 확인한다
- [ ] ADMIN 전용 작업(고객 비활성화 등)에서 STAFF가 403을 받는다
- [ ] 역할 기반 접근 제어가 구현돼 있다
- [ ] 세션 관리가 안전하다

### 5. XSS 방지

#### HTML 정제
```typescript
import DOMPurify from 'isomorphic-dompurify'

// 사용자가 입력한 HTML은 항상 정제한다
function renderUserContent(html: string) {
  const clean = DOMPurify.sanitize(html, {
    ALLOWED_TAGS: ['b', 'i', 'em', 'strong', 'p'],
    ALLOWED_ATTR: []
  })
  return <div dangerouslySetInnerHTML={{ __html: clean }} />
}
```

#### 콘텐츠 보안 정책 (CSP)

엄격하게 시작하고, 완화할 때는 언제 되돌릴지 계획을 문서로 남긴다. `'unsafe-inline'`이나
`'unsafe-eval'`을 기본으로 쓰지 않는다 — CSP 보호의 상당 부분을 무력화하므로
임시 호환성 부채로 취급한다.

```typescript
// next.config.js
const securityHeaders = [
  {
    key: 'Content-Security-Policy',
    value: `
      default-src 'self';
      base-uri 'self';
      object-src 'none';
      frame-ancestors 'none';
      script-src 'self';
      style-src 'self';
      img-src 'self' data: https:;
      font-src 'self';
      connect-src 'self' https://api.example.com;
    `.replace(/\s{2,}/g, ' ').trim()
  }
]
```

#### 확인 항목
- [ ] 사용자 입력 HTML을 정제한다
- [ ] CSP 헤더가 설정돼 있다
- [ ] 검증되지 않은 동적 콘텐츠를 렌더링하지 않는다
- [ ] React의 기본 XSS 방어를 활용한다 (`dangerouslySetInnerHTML` 지양)

### 6. CSRF 방어

#### CSRF 토큰
```typescript
import { csrf } from '@/lib/csrf'

export async function POST(request: Request) {
  const token = request.headers.get('X-CSRF-Token')

  if (!csrf.verify(token)) {
    return NextResponse.json(
      { error: 'Invalid CSRF token' },
      { status: 403 }
    )
  }

  // 요청 처리
}
```

#### SameSite 쿠키
```typescript
res.setHeader('Set-Cookie',
  `session=${sessionId}; HttpOnly; Secure; SameSite=Strict`)
```

#### 확인 항목
- [ ] 상태를 바꾸는 작업에 CSRF 토큰이 있다 (Bearer 토큰만 쓰면 해당 없음)
- [ ] 모든 쿠키가 SameSite=Strict다
- [ ] 이중 제출 쿠키(double-submit) 패턴이 구현돼 있다

### 7. 레이트 리밋

#### API 레이트 리밋
```typescript
import rateLimit from 'express-rate-limit'

const limiter = rateLimit({
  windowMs: 15 * 60 * 1000, // 15분
  max: 100, // 구간당 요청 100건
  message: 'Too many requests'
})

// 경로에 적용
app.use('/api/', limiter)
```

#### 비용이 큰 작업
```typescript
// 검색은 더 엄격하게 제한
const searchLimiter = rateLimit({
  windowMs: 60 * 1000, // 1분
  max: 10, // 분당 요청 10건
  message: 'Too many search requests'
})

app.use('/api/search', searchLimiter)
```

#### 확인 항목
- [ ] 모든 API 엔드포인트에 레이트 리밋이 있다
- [ ] 비용이 큰 작업에는 더 엄격한 제한이 있다
- [ ] IP 기준 레이트 리밋 (특히 로그인 무차별 대입 방어)
- [ ] 사용자 기준 레이트 리밋 (인증된 요청)

### 8. 민감정보 노출

#### 로깅
```typescript
// 나쁨: 민감정보를 로그에 남김
console.log('User login:', { email, password })
console.log('Payment:', { cardNumber, cvv })

// 좋음: 민감정보를 가림
console.log('User login:', { email, userId })
console.log('Payment:', { last4: card.last4, userId })
```

#### 오류 메시지
```typescript
// 나쁨: 내부 정보 노출
catch (error) {
  return NextResponse.json(
    { error: error.message, stack: error.stack },
    { status: 500 }
  )
}

// 좋음: 일반적인 오류 메시지
catch (error) {
  console.error('Internal error:', error)
  return NextResponse.json(
    { error: 'An error occurred. Please try again.' },
    { status: 500 }
  )
}
```

#### 확인 항목
- [ ] 로그에 비밀번호, 토큰, 시크릿이 없다 (CreditBook: 고객 이름·연락처도 없다)
- [ ] 사용자에게 보이는 오류 메시지는 일반적이다
- [ ] 자세한 오류는 서버 로그에만 있다
- [ ] 스택트레이스가 사용자에게 노출되지 않는다

### 9. 블록체인 보안 (Solana — CreditBook 해당 없음, 참고용)

#### 지갑 검증
```typescript
import { verify } from '@solana/web3.js'

async function verifyWalletOwnership(
  publicKey: string,
  signature: string,
  message: string
) {
  try {
    const isValid = verify(
      Buffer.from(message),
      Buffer.from(signature, 'base64'),
      Buffer.from(publicKey, 'base64')
    )
    return isValid
  } catch (error) {
    return false
  }
}
```

#### 거래 검증
```typescript
async function verifyTransaction(transaction: Transaction) {
  // 수신자 확인
  if (transaction.to !== expectedRecipient) {
    throw new Error('Invalid recipient')
  }

  // 금액 확인
  if (transaction.amount > maxAmount) {
    throw new Error('Amount exceeds limit')
  }

  // 잔액이 충분한지 확인
  const balance = await getBalance(transaction.from)
  if (balance < transaction.amount) {
    throw new Error('Insufficient balance')
  }

  return true
}
```

#### 확인 항목
- [ ] 지갑 서명을 검증한다
- [ ] 거래 내용을 검증한다
- [ ] 거래 전에 잔액을 확인한다
- [ ] 내용을 확인하지 않고 서명하지 않는다

### 10. 의존성 보안

#### 정기 업데이트
```bash
# 취약점 확인
npm audit

# 자동으로 고칠 수 있는 것 고치기
npm audit fix

# 의존성 업데이트
npm update

# 오래된 패키지 확인
npm outdated
```

#### 락 파일
```bash
# 락 파일은 항상 커밋한다
git add package-lock.json

# CI/CD에서는 재현 가능한 빌드를 위해
npm ci  # npm install 대신
```

#### 확인 항목
- [ ] 의존성이 최신이다
- [ ] 알려진 취약점이 없다 (npm audit / OWASP Dependency Check 통과)
- [ ] 락 파일이 커밋돼 있다
- [ ] GitHub Dependabot이 켜져 있다
- [ ] 보안 업데이트를 정기적으로 한다

## 보안 테스트

### 자동화된 보안 테스트
```typescript
// 인증 테스트
test('requires authentication', async () => {
  const response = await fetch('/api/protected')
  expect(response.status).toBe(401)
})

// 인가 테스트
test('requires admin role', async () => {
  const response = await fetch('/api/admin', {
    headers: { Authorization: `Bearer ${userToken}` }
  })
  expect(response.status).toBe(403)
})

// 입력 검증 테스트
test('rejects invalid input', async () => {
  const response = await fetch('/api/users', {
    method: 'POST',
    body: JSON.stringify({ email: 'not-an-email' })
  })
  expect(response.status).toBe(400)
})

// 레이트 리밋 테스트
test('enforces rate limits', async () => {
  const requests = Array(101).fill(null).map(() =>
    fetch('/api/endpoint')
  )

  const responses = await Promise.all(requests)
  const tooManyRequests = responses.filter(r => r.status === 429)

  expect(tooManyRequests.length).toBeGreaterThan(0)
})
```

## 배포 전 보안 체크리스트

운영 배포 전에는 반드시:

- [ ] **시크릿**: 하드코딩 없음, 모두 환경변수
- [ ] **입력 검증**: 모든 사용자 입력 검증
- [ ] **SQL 인젝션**: 모든 쿼리 파라미터 바인딩
- [ ] **XSS**: 사용자 콘텐츠 정제
- [ ] **CSRF**: 방어 설정
- [ ] **인증**: 토큰을 올바르게 처리
- [ ] **인가**: 역할 확인 존재
- [ ] **레이트 리밋**: 모든 엔드포인트에 적용
- [ ] **HTTPS**: 운영에서 강제
- [ ] **보안 헤더**: CSP, X-Frame-Options 설정
- [ ] **오류 처리**: 오류에 민감정보 없음
- [ ] **로깅**: 민감정보를 로그에 남기지 않음
- [ ] **의존성**: 최신, 취약점 없음
- [ ] **DB 권한**: 애플리케이션 계정의 `ledger_entries` UPDATE/DELETE 차단 (CreditBook 절대 금지 1번)
- [ ] **CORS**: 올바르게 설정
- [ ] **파일 업로드**: 검증 (크기, 타입)
- [ ] **지갑 서명**: 검증 (블록체인인 경우, CreditBook 해당 없음)

## 참고 자료

- [OWASP Top 10](https://owasp.org/www-project-top-ten/)
- [Next.js Security](https://nextjs.org/docs/security)
- [Supabase Security](https://supabase.com/docs/guides/auth)
- [Web Security Academy](https://portswigger.net/web-security)

---

**기억할 것**: 보안은 선택이 아니다. 취약점 하나가 플랫폼 전체를 무너뜨릴 수 있다. 확신이 없으면 안전한 쪽을 택한다.

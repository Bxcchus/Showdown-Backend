const CLIENT_ID = 'pinkward-web'
const SCOPES = 'openid profile:read profile:write party:manage queue:write match:read match:ready'
const SESSION_KEY = 'showdown.oauth.session'
const FLOW_KEY = 'showdown.oauth.flow'
const LOGOUT_KEY = 'showdown.oauth.logout'
const LOGOUT_CHANNEL = 'showdown.oauth.events'

interface TokenResponse {
  access_token: string
  refresh_token?: string
  expires_in: number
  scope: string
  token_type: string
}

interface TokenClaims {
  sub: string
  preferred_username?: string
}

interface AuthorizationFlow {
  state: string
  verifier: string
  redirectUri: string
}

export interface UserSession {
  accessToken: string
  refreshToken?: string
  expiresAt: number
  playerId: string
  username: string
}

const usesDevelopmentProxy = () => window.location.port === '3000'
export const apiPath = (path: string) => `${usesDevelopmentProxy() ? '/backend' : ''}${path}`

function identityOrigin() {
  return usesDevelopmentProxy() ? 'http://localhost:8088' : window.location.origin
}

function redirectUri() {
  return `${window.location.origin}/oauth/callback`
}

function randomUrlSafe(bytes: number) {
  const value = new Uint8Array(bytes)
  crypto.getRandomValues(value)
  return base64Url(value)
}

function base64Url(value: Uint8Array) {
  let binary = ''
  value.forEach((byte) => { binary += String.fromCharCode(byte) })
  return btoa(binary).replaceAll('+', '-').replaceAll('/', '_').replaceAll('=', '')
}

async function codeChallenge(verifier: string) {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier))
  return base64Url(new Uint8Array(digest))
}

function decodeClaims(token: string): TokenClaims {
  const payload = token.split('.')[1].replaceAll('-', '+').replaceAll('_', '/')
  const padding = '='.repeat((4 - payload.length % 4) % 4)
  return JSON.parse(atob(payload + padding)) as TokenClaims
}

function createSession(tokens: TokenResponse): UserSession {
  const claims = decodeClaims(tokens.access_token)
  return {
    accessToken: tokens.access_token,
    refreshToken: tokens.refresh_token,
    expiresAt: Date.now() + tokens.expires_in * 1000,
    playerId: claims.sub,
    username: claims.preferred_username ?? 'Joueur Pinkward',
  }
}

function saveSession(session: UserSession) {
  sessionStorage.setItem(SESSION_KEY, JSON.stringify(session))
  return session
}

export function readSession(): UserSession | null {
  const serialized = sessionStorage.getItem(SESSION_KEY)
  if (!serialized) return null
  try {
    return JSON.parse(serialized) as UserSession
  } catch {
    sessionStorage.removeItem(SESSION_KEY)
    return null
  }
}

export async function beginLogin() {
  const verifier = randomUrlSafe(64)
  const flow: AuthorizationFlow = {
    state: randomUrlSafe(32),
    verifier,
    redirectUri: redirectUri(),
  }
  sessionStorage.setItem(FLOW_KEY, JSON.stringify(flow))

  const parameters = new URLSearchParams({
    response_type: 'code',
    client_id: CLIENT_ID,
    scope: SCOPES,
    redirect_uri: flow.redirectUri,
    state: flow.state,
    code_challenge: await codeChallenge(verifier),
    code_challenge_method: 'S256',
  })
  window.location.assign(`${identityOrigin()}/oauth2/authorize?${parameters}`)
}

let callbackExchange: Promise<UserSession | null> | null = null

export function completeLogin(): Promise<UserSession | null> {
  if (callbackExchange) return callbackExchange
  callbackExchange = exchangeCallback()
  return callbackExchange
}

async function exchangeCallback(): Promise<UserSession | null> {
  if (window.location.pathname !== '/oauth/callback') return readSession()

  const query = new URLSearchParams(window.location.search)
  const error = query.get('error')
  if (error) throw new Error(query.get('error_description') ?? error)

  const code = query.get('code')
  const state = query.get('state')
  const serializedFlow = sessionStorage.getItem(FLOW_KEY)
  if (!code || !state || !serializedFlow) throw new Error('Réponse de connexion incomplète')

  const flow = JSON.parse(serializedFlow) as AuthorizationFlow
  if (state !== flow.state) throw new Error('État OAuth2 invalide')

  const response = await fetch(apiPath('/oauth2/token'), {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'authorization_code',
      client_id: CLIENT_ID,
      redirect_uri: flow.redirectUri,
      code,
      code_verifier: flow.verifier,
    }),
  })
  if (!response.ok) throw new Error('Échange du code OAuth2 refusé')

  sessionStorage.removeItem(FLOW_KEY)
  const session = saveSession(createSession(await response.json() as TokenResponse))
  window.history.replaceState({}, '', '/')
  return session
}

async function refreshSession(session: UserSession): Promise<UserSession | null> {
  if (!session.refreshToken) return null
  const response = await fetch(apiPath('/oauth2/token'), {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'refresh_token',
      client_id: CLIENT_ID,
      refresh_token: session.refreshToken,
    }),
  })
  if (!response.ok) return null
  const refreshed = createSession(await response.json() as TokenResponse)
  if (!refreshed.refreshToken) refreshed.refreshToken = session.refreshToken
  return saveSession(refreshed)
}

export async function validSession(forceRefresh = false): Promise<UserSession | null> {
  const current = readSession()
  if (!current) return null
  if (!forceRefresh && current.expiresAt > Date.now() + 15_000) return current
  const refreshed = await refreshSession(current)
  if (!refreshed) void signOut()
  return refreshed
}

export async function authenticatedFetch(path: string, init: RequestInit = {}) {
  let session = await validSession()
  if (!session) throw new Error('Connexion requise')

  const request = (accessToken: string) => fetch(apiPath(path), {
    ...init,
    headers: {
      ...init.headers,
      Authorization: `Bearer ${accessToken}`,
    },
  })

  let response = await request(session.accessToken)
  if (response.status === 401) {
    session = await validSession(true)
    if (session) response = await request(session.accessToken)
  }
  return response
}

function announceSignOut() {
  try {
    const channel = new BroadcastChannel(LOGOUT_CHANNEL)
    channel.postMessage('signed-out')
    channel.close()
  } catch {
    // Storage remains the compatibility signal when BroadcastChannel is unavailable.
  }
  localStorage.setItem(LOGOUT_KEY, crypto.randomUUID())
  localStorage.removeItem(LOGOUT_KEY)
}

export function subscribeToSignOut(listener: () => void) {
  const handleRemoteSignOut = () => {
    sessionStorage.removeItem(SESSION_KEY)
    sessionStorage.removeItem(FLOW_KEY)
    listener()
  }
  const storageListener = (event: StorageEvent) => {
    if (event.key === LOGOUT_KEY) handleRemoteSignOut()
  }
  window.addEventListener('storage', storageListener)
  let channel: BroadcastChannel | null = null
  try {
    channel = new BroadcastChannel(LOGOUT_CHANNEL)
    channel.addEventListener('message', handleRemoteSignOut)
  } catch {
    channel = null
  }
  return () => {
    window.removeEventListener('storage', storageListener)
    channel?.close()
  }
}

export async function signOut() {
  const session = readSession()
  sessionStorage.removeItem(SESSION_KEY)
  sessionStorage.removeItem(FLOW_KEY)
  announceSignOut()
  if (!session?.refreshToken) return
  try {
    await fetch(apiPath('/oauth2/revoke'), {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({
        client_id: CLIENT_ID,
        token: session.refreshToken,
        token_type_hint: 'refresh_token',
      }),
    })
  } catch {
    // The local session is cleared even when the identity service is unavailable.
  }
}

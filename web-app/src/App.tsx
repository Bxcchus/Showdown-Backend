import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  apiPath,
  authenticatedFetch,
  beginLogin,
  completeLogin,
  readSession,
  signOut,
  subscribeToSignOut,
  type UserSession,
} from './auth'

type Region = 'EUW' | 'EUNE' | 'NA'
type LaneRole = 'TOP' | 'JUNGLE' | 'MID' | 'BOT' | 'SUPPORT'
type MatchMode = 'FIVE_V_FIVE' | 'ONE_V_ONE'

const laneRoles: LaneRole[] = ['TOP', 'JUNGLE', 'MID', 'BOT', 'SUPPORT']
const BOT_WATCHER_MATCH_KEY = 'showdown.bot-watcher.match-id'
const laneLabels: Record<LaneRole, string> = {
  TOP: 'Top',
  JUNGLE: 'Jungle',
  MID: 'Mid',
  BOT: 'ADC',
  SUPPORT: 'Support',
}
const laneMarks: Record<LaneRole, string> = {
  TOP: 'T',
  JUNGLE: 'J',
  MID: 'M',
  BOT: 'A',
  SUPPORT: 'S',
}

interface QueueEntry {
  queueEntryId: string
  playerId: string
  partyId: string | null
  region: Region
  mode: string
  status: 'QUEUED' | 'RESERVED'
  joinedAt: string
  reservationId: string | null
  primaryRole: LaneRole
  secondaryRole: LaneRole
  mmr: number
}

interface MatchPlayer {
  playerId: string
  team: 'BLUE' | 'RED'
  readyState: 'PENDING' | 'ACCEPTED' | 'DECLINED'
  bot: boolean
  assignedRole: LaneRole
}

interface CurrentMatch {
  matchId: string
  reservationId: string
  region: Region
  mode: string
  status: 'READY_CHECK' | 'CONFIRMED' | 'CANCELLED' | 'EXPIRED' | 'COMPLETED'
  createdAt: string
  readyDeadline: string
  lobbyName: string | null
  lobbyPassword: string | null
  winningTeam: 'BLUE' | 'RED' | null
  completedAt: string | null
  players: MatchPlayer[]
}

interface MatchHistoryEntry {
  matchId: string
  region: Region
  mode: string
  outcome: 'VICTORY' | 'DEFEAT'
  team: 'BLUE' | 'RED'
  role: LaneRole
  playedAt: string
  previousMmr: number
  mmrDelta: number
  newMmr: number
}

interface MatchHistoryPage {
  content: MatchHistoryEntry[]
  page: number
  size: number
  totalElements: number
  totalPages: number
  hasPrevious: boolean
  hasNext: boolean
}

interface MatchParticipantDetail {
  playerId: string
  team: 'BLUE' | 'RED'
  role: LaneRole
  bot: boolean
  self: boolean
}

interface MatchDetail {
  summary: MatchHistoryEntry
  teammates: MatchParticipantDetail[]
  opponents: MatchParticipantDetail[]
}

interface HistoryQuery {
  page: number
  region: '' | Region
  role: '' | LaneRole
  outcome: '' | 'VICTORY' | 'DEFEAT'
}

interface MatchStatistics {
  mmr: number
  peakMmr: number
  skillMean: number
  skillDeviation: number
  season: string
  region: Region
  placementGamesRemaining: number
  progression: number
  rank: string
  games: number
  wins: number
  losses: number
  winRate: number
  gamesByRole: Partial<Record<LaneRole, number>>
  recentForm: Array<'VICTORY' | 'DEFEAT'>
}

interface LeaderboardEntry {
  position: number
  playerId: string
  mmr: number
  rank: string
  games: number
  wins: number
  winRate: number
  progression: number
}

interface LeaderboardSnapshot {
  season: string
  region: Region
  startsAt: string
  endsAt: string
  placementGames: number
  entries: LeaderboardEntry[]
}

interface DuelStatistics {
  mmr: number
  peakMmr: number
  rating: number
  ratingDeviation: number
  volatility: number
  algorithm: 'GLICKO_2'
  season: string
  region: Region
  placementGamesRemaining: number
  provisional: boolean
  progression: number
  games: number
  wins: number
  losses: number
  winRate: number
}

interface DuelLeaderboardEntry {
  position: number
  playerId: string
  mmr: number
  ratingDeviation: number
  provisional: boolean
  games: number
  wins: number
  winRate: number
  progression: number
}

interface DuelLeaderboardSnapshot {
  algorithm: 'GLICKO_2'
  season: string
  region: Region
  startsAt: string
  endsAt: string
  placementGames: number
  entries: DuelLeaderboardEntry[]
}

interface PlayerProfile {
  playerId: string
  displayName: string
  region: Region
  primaryRole: LaneRole
  secondaryRole: LaneRole
  riotGameName: string | null
  riotTagLine: string | null
  riotId: string | null
  riotLinkedAt: string | null
  online: boolean
  lastSeenAt: string
  updatedAt: string
}

interface DirectoryPlayer {
  playerId: string
  displayName: string
  riotLinked: boolean
}

interface DuelChallenge {
  challengeId: string
  challengerId: string
  opponentId: string
  region: Region
  status: 'PENDING' | 'ACCEPTED' | 'COMPLETED' | 'DECLINED' | 'CANCELLED' | 'EXPIRED'
  matchId: string | null
  incoming: boolean
  host: boolean
  createdAt: string
  expiresAt: string
}

interface WatcherIdentity {
  puuid: string
  gameName: string
  tagLine: string
  riotId: string
  profileIconId: number
  summonerLevel: number
}
interface WatcherSession { token: string }
interface RiotLinkChallenge { challengeId: string, expiresAt: string }
interface WatcherToken { token: string, matchId: string, role: 'HOST' | 'GUEST', expiresAt: string }
interface WatcherJob {
  matchId: string | null
  state: string
  detail: string | null
  outcome: 'VICTORY' | 'DEFEAT' | null
  objective: 'FIRST_BLOOD' | 'FIRST_TOWER' | 'FIRST_TO_100_CS' | null
}

const WATCHER_BASE_URL = 'http://127.0.0.1:43991'
let watcherSessionToken: string | null = null
let watcherSessionRequest: Promise<string> | null = null

async function createWatcherSession(): Promise<string> {
  if (watcherSessionToken) return watcherSessionToken
  if (!watcherSessionRequest) {
    watcherSessionRequest = fetch(`${WATCHER_BASE_URL}/v1/session`, { method: 'POST' })
      .then(async response => {
        if (!response.ok) throw new Error('Le watcher local a refusé la session de ce site.')
        const session = await response.json() as WatcherSession
        if (!session.token) throw new Error('Le watcher a renvoyé une session locale invalide.')
        watcherSessionToken = session.token
        return session.token
      })
      .finally(() => { watcherSessionRequest = null })
  }
  return watcherSessionRequest
}

async function watcherFetch(path: string, init: RequestInit = {}, retry = true): Promise<Response> {
  const token = await createWatcherSession()
  const headers = new Headers(init.headers)
  headers.set('X-Showdown-Watcher-Token', token)
  const response = await fetch(`${WATCHER_BASE_URL}${path}`, { ...init, headers })
  if (retry && (response.status === 401 || response.status === 403)) {
    watcherSessionToken = null
    return watcherFetch(path, init, false)
  }
  return response
}

interface PartyMember {
  playerId: string
  displayName: string
  primaryRole: LaneRole
  secondaryRole: LaneRole
  ready: boolean
  online: boolean
  simulated: boolean
}

interface PartyInvitation {
  invitationId: string
  inviteeId: string
  displayName: string
  status: string
  expiresAt: string
}

interface IncomingPartyInvitation {
  invitationId: string
  partyId: string
  inviterDisplayName: string
  region: Region
  expiresAt: string
}

interface PartySnapshot {
  partyId: string
  leaderId: string
  region: Region
  viewerIsLeader: boolean
  allReady: boolean
  capacity: number
  members: PartyMember[]
  invitations: PartyInvitation[]
}

function formatElapsed(joinedAt: string) {
  const elapsed = Math.max(0, Date.now() - new Date(joinedAt).getTime())
  const minutes = Math.floor(elapsed / 60_000).toString().padStart(2, '0')
  const seconds = Math.floor(elapsed % 60_000 / 1000).toString().padStart(2, '0')
  return `${minutes}:${seconds}`
}

function App() {
  const [session, setSession] = useState<UserSession | null>(() => readSession())
  const [queue, setQueue] = useState<QueueEntry | null>(null)
  const [match, setMatch] = useState<CurrentMatch | null>(null)
  const [lobby, setLobby] = useState<CurrentMatch | null>(null)
  const [profile, setProfile] = useState<PlayerProfile | null>(null)
  const [profileOpen, setProfileOpen] = useState(false)
  const [party, setParty] = useState<PartySnapshot | null>(null)
  const [partyOpen, setPartyOpen] = useState(false)
  const [history, setHistory] = useState<MatchHistoryEntry[]>([])
  const [historyPage, setHistoryPage] = useState<MatchHistoryPage | null>(null)
  const [historyQuery, setHistoryQuery] = useState<HistoryQuery>({ page: 0, region: '', role: '', outcome: '' })
  const [historyDetail, setHistoryDetail] = useState<MatchDetail | null>(null)
  const [historyNames, setHistoryNames] = useState<Record<string, string>>({})
  const [statistics, setStatistics] = useState<MatchStatistics | null>(null)
  const [duelStatistics, setDuelStatistics] = useState<DuelStatistics | null>(null)
  const [historyOpen, setHistoryOpen] = useState(false)
  const [leaderboardOpen, setLeaderboardOpen] = useState(false)
  const [leaderboard, setLeaderboard] = useState<LeaderboardSnapshot | null>(null)
  const [duelLeaderboard, setDuelLeaderboard] = useState<DuelLeaderboardSnapshot | null>(null)
  const [leaderboardNames, setLeaderboardNames] = useState<Record<string, string>>({})
  const [historyMessage, setHistoryMessage] = useState<string | null>(null)
  const [incomingInvitations, setIncomingInvitations] = useState<IncomingPartyInvitation[]>([])
  const [inviteName, setInviteName] = useState('')
  const [partyMessage, setPartyMessage] = useState<string | null>(null)
  const [profileName, setProfileName] = useState('')
  const [profileRegion, setProfileRegion] = useState<Region>('EUW')
  const [profilePrimaryRole, setProfilePrimaryRole] = useState<LaneRole>('MID')
  const [profileSecondaryRole, setProfileSecondaryRole] = useState<LaneRole>('JUNGLE')
  const [profileMessage, setProfileMessage] = useState<string | null>(null)
  const [duelOpen, setDuelOpen] = useState(false)
  const [duelChallenges, setDuelChallenges] = useState<DuelChallenge[]>([])
  const [duelPlayerNames, setDuelPlayerNames] = useState<Record<string, string>>({})
  const [duelOpponentName, setDuelOpponentName] = useState('local-player2')
  const [duelMessage, setDuelMessage] = useState<string | null>(null)
  const [watcherOnline, setWatcherOnline] = useState(false)
  const [watcherJob, setWatcherJob] = useState<WatcherJob | null>(null)
  const [selectedMode, setSelectedMode] = useState<MatchMode>('FIVE_V_FIVE')
  const [region, setRegion] = useState<Region>('EUW')
  const [primaryRole, setPrimaryRole] = useState<LaneRole>('MID')
  const [secondaryRole, setSecondaryRole] = useState<LaneRole>('JUNGLE')
  const [online, setOnline] = useState<boolean | null>(null)
  const [realtimeConnected, setRealtimeConnected] = useState(false)
  const [busy, setBusy] = useState(window.location.pathname === '/oauth/callback')
  const [message, setMessage] = useState<string | null>(null)
  const [copiedCredential, setCopiedCredential] = useState<'name' | 'password' | null>(null)
  const [clock, setClock] = useState(Date.now())
  const profileRegionRef = useRef<Region>('EUW')
  const historyQueryRef = useRef<HistoryQuery>({ page: 0, region: '', role: '', outcome: '' })
  const reportedWatcherMatchRef = useRef<string | null>(null)

  useEffect(() => subscribeToSignOut(() => setSession(null)), [])

  const loadQueue = useCallback(async () => {
    if (!readSession()) return
    const response = await authenticatedFetch('/api/v2/matchmaking/queue')
    if (response.status === 404) {
      setQueue(null)
      return
    }
    if (!response.ok) throw new Error('Impossible de lire la file')
    const entry = await response.json() as QueueEntry
    setQueue(entry)
    setSelectedMode(entry.mode === 'ONE_V_ONE' ? 'ONE_V_ONE' : 'FIVE_V_FIVE')
    setRegion(entry.region)
    setPrimaryRole(entry.primaryRole)
    setSecondaryRole(entry.secondaryRole)
  }, [])

  const loadMatch = useCallback(async () => {
    if (!readSession()) return
    const response = await authenticatedFetch('/api/v2/matches/current')
    if (response.status === 404) {
      setMatch(null)
      return
    }
    if (!response.ok) throw new Error('Impossible de lire le match courant')
    setMatch(await response.json() as CurrentMatch)
  }, [])

  const loadLobby = useCallback(async () => {
    if (!readSession()) return
    const response = await authenticatedFetch('/api/v2/matches/current-lobby')
    if (response.status === 404) {
      setLobby(null)
      return
    }
    if (!response.ok) throw new Error('Impossible de lire le lobby courant')
    setLobby(await response.json() as CurrentMatch)
  }, [])

  const applyProfile = useCallback((updated: PlayerProfile) => {
    setProfile(updated)
    setProfileName(updated.displayName)
    setProfileRegion(updated.region)
    setProfilePrimaryRole(updated.primaryRole)
    setProfileSecondaryRole(updated.secondaryRole)
    profileRegionRef.current = updated.region
  }, [])

  const loadProfile = useCallback(async () => {
    if (!readSession()) return
    const response = await authenticatedFetch('/api/v2/players/me')
    if (response.status === 403) {
      throw new Error('Reconnecte-toi une fois pour activer les permissions du profil.')
    }
    if (!response.ok) throw new Error('Impossible de charger ton profil')
    applyProfile(await response.json() as PlayerProfile)
  }, [applyProfile])

  const loadParty = useCallback(async () => {
    if (!readSession()) return
    const response = await authenticatedFetch('/api/v2/parties/current')
    if (response.status === 404) {
      setParty(null)
      return
    }
    if (!response.ok) throw new Error('Impossible de charger ton groupe')
    setParty(await response.json() as PartySnapshot)
  }, [])

  const loadPartyInvitations = useCallback(async () => {
    if (!readSession()) return
    const response = await authenticatedFetch('/api/v2/parties/invitations')
    if (!response.ok) throw new Error('Impossible de charger les invitations')
    setIncomingInvitations(await response.json() as IncomingPartyInvitation[])
  }, [])

  const loadDuels = useCallback(async () => {
    if (!readSession()) return
    const response = await authenticatedFetch('/api/v2/matches/duels')
    if (!response.ok) throw new Error('Impossible de charger les duels')
    const challenges = await response.json() as DuelChallenge[]
    setDuelChallenges(challenges)
    const playerIds = [...new Set(challenges.flatMap(challenge => [challenge.challengerId, challenge.opponentId]))]
    if (playerIds.length === 0) {
      setDuelPlayerNames({})
      return
    }
    const namesResponse = await authenticatedFetch(`/api/v2/players/directory?ids=${encodeURIComponent(playerIds.join(','))}`)
    if (namesResponse.ok) {
      const players = await namesResponse.json() as DirectoryPlayer[]
      setDuelPlayerNames(Object.fromEntries(players.map(player => [player.playerId, player.displayName])))
    }
  }, [])

  const loadHistory = useCallback(async () => {
    if (!readSession()) return
    const query = historyQueryRef.current
    const params = new URLSearchParams({ page: String(query.page), size: '10' })
    if (query.region) params.set('region', query.region)
    if (query.role) params.set('role', query.role)
    if (query.outcome) params.set('outcome', query.outcome)
    const [historyResponse, statisticsResponse, duelStatisticsResponse] = await Promise.all([
      authenticatedFetch(`/api/v2/matches/history?${params}`),
      authenticatedFetch(`/api/v2/matches/statistics?region=${profileRegionRef.current}`),
      authenticatedFetch(`/api/v2/matches/duel/statistics?region=${profileRegionRef.current}`),
    ])
    if (historyResponse.status === 403 || statisticsResponse.status === 403 || duelStatisticsResponse.status === 403) {
      throw new Error('Reconnecte-toi une fois pour activer les résultats et le MMR.')
    }
    if (!historyResponse.ok || !statisticsResponse.ok || !duelStatisticsResponse.ok) throw new Error('Impossible de charger l’historique')
    const page = await historyResponse.json() as MatchHistoryPage
    setHistory(page.content)
    setHistoryPage(page)
    setStatistics(await statisticsResponse.json() as MatchStatistics)
    setDuelStatistics(await duelStatisticsResponse.json() as DuelStatistics)
  }, [])

  const updateHistoryQuery = useCallback(async (patch: Partial<HistoryQuery>) => {
    const next = { ...historyQueryRef.current, ...patch }
    historyQueryRef.current = next
    setHistoryQuery(next)
    setHistoryMessage(null)
    try {
      await loadHistory()
    } catch (error) {
      setHistoryMessage(error instanceof Error ? error.message : 'Chargement impossible')
    }
  }, [loadHistory])

  const openMatchDetail = useCallback(async (matchId: string) => {
    setHistoryMessage(null)
    try {
      const response = await authenticatedFetch(`/api/v2/matches/history/${matchId}`)
      if (!response.ok) throw new Error('Impossible de charger la fiche du match')
      const detail = await response.json() as MatchDetail
      setHistoryDetail(detail)
      const ids = [...detail.teammates, ...detail.opponents]
        .filter(player => !player.bot)
        .map(player => player.playerId)
      if (ids.length) {
        const namesResponse = await authenticatedFetch(`/api/v2/players/directory?ids=${encodeURIComponent(ids.join(','))}`)
        if (namesResponse.ok) {
          const names = await namesResponse.json() as Array<{ playerId: string, displayName: string }>
          setHistoryNames(Object.fromEntries(names.map(entry => [entry.playerId, entry.displayName])))
        }
      }
    } catch (error) {
      setHistoryMessage(error instanceof Error ? error.message : 'Chargement impossible')
    }
  }, [])

  const loadLeaderboard = useCallback(async () => {
    if (!readSession()) return
    const [response, duelResponse] = await Promise.all([
      authenticatedFetch(`/api/v2/matches/leaderboard?region=${profileRegionRef.current}&limit=50`),
      authenticatedFetch(`/api/v2/matches/duel/leaderboard?region=${profileRegionRef.current}&limit=50`),
    ])
    if (!response.ok || !duelResponse.ok) throw new Error('Impossible de charger le classement')
    const snapshot = await response.json() as LeaderboardSnapshot
    const duelSnapshot = await duelResponse.json() as DuelLeaderboardSnapshot
    setLeaderboard(snapshot)
    setDuelLeaderboard(duelSnapshot)
    const playerIds = [...new Set([
      ...snapshot.entries.map(entry => entry.playerId),
      ...duelSnapshot.entries.map(entry => entry.playerId),
    ])]
    if (playerIds.length > 0) {
      const ids = playerIds.join(',')
      const namesResponse = await authenticatedFetch(`/api/v2/players/directory?ids=${encodeURIComponent(ids)}`)
      if (namesResponse.ok) {
        const names = await namesResponse.json() as Array<{ playerId: string, displayName: string }>
        setLeaderboardNames(Object.fromEntries(names.map(entry => [entry.playerId, entry.displayName])))
      }
    }
  }, [])

  useEffect(() => {
    fetch(apiPath('/actuator/health/readiness'))
      .then((response) => response.ok ? response.json() : Promise.reject())
      .then((health: { status: string }) => setOnline(health.status === 'UP'))
      .catch(() => setOnline(false))

    completeLogin()
      .then((connected) => {
        setSession(connected)
        if (connected) return Promise.all([
          loadQueue(), loadMatch(), loadLobby(), loadProfile(), loadParty(), loadPartyInvitations(), loadHistory(), loadDuels(),
        ])
      })
      .catch((error: unknown) => {
        setMessage(error instanceof Error ? error.message : 'Connexion impossible')
      })
      .finally(() => setBusy(false))
  }, [loadDuels, loadHistory, loadLobby, loadMatch, loadParty, loadPartyInvitations, loadProfile, loadQueue])

  useEffect(() => {
    if (!profile || queue) return
    setRegion(profile.region)
    setPrimaryRole(profile.primaryRole)
    setSecondaryRole(profile.secondaryRole)
  }, [profile, queue])

  useEffect(() => {
    if (!session) return
    let stopped = false
    let retry: number | undefined
    let sockets: WebSocket[] = []
    let connected = 0

    const refreshParty = () => void Promise.allSettled([
      loadParty(), loadPartyInvitations(), loadQueue(),
    ])
    const refreshMatch = () => void Promise.allSettled([
      loadQueue(), loadMatch(), loadLobby(), loadHistory(), loadDuels(),
    ])
    const connect = () => {
      const base = window.location.port === '3000' ? 'http://localhost:8088' : window.location.origin
      const websocketBase = base.replace(/^http/, 'ws')
      sockets = [
        new WebSocket(`${websocketBase}/api/v2/realtime/party`, ['showdown-v1', `bearer.${session.accessToken}`]),
        new WebSocket(`${websocketBase}/api/v2/realtime/matches`, ['showdown-v1', `bearer.${session.accessToken}`]),
      ]
      sockets.forEach((socket, index) => {
        socket.onopen = () => {
          connected++
          if (connected === sockets.length) setRealtimeConnected(true)
        }
        socket.onmessage = () => index === 0 ? refreshParty() : refreshMatch()
        socket.onerror = () => socket.close()
        socket.onclose = () => {
          connected = Math.max(0, connected - 1)
          setRealtimeConnected(false)
          if (!stopped && retry === undefined) {
            retry = window.setTimeout(() => {
              retry = undefined
              sockets.forEach(candidate => candidate.close())
              connect()
            }, 1500)
          }
        }
      })
    }

    connect()
    return () => {
      stopped = true
      if (retry !== undefined) window.clearTimeout(retry)
      sockets.forEach(socket => socket.close())
      setRealtimeConnected(false)
    }
  }, [loadDuels, loadHistory, loadLobby, loadMatch, loadParty, loadPartyInvitations, loadQueue, session])

  useEffect(() => {
    if (!session) return

    const heartbeat = async () => {
      try {
        const response = await authenticatedFetch('/api/v2/players/me/presence', { method: 'POST' })
        if (response.ok) applyProfile(await response.json() as PlayerProfile)
      } catch {
        // Le prochain battement réessaiera ; l'état réseau général reste affiché séparément.
      }
    }
    const onVisibilityChange = () => {
      if (document.visibilityState === 'visible') void heartbeat()
    }

    void heartbeat()
    const interval = window.setInterval(heartbeat, 20_000)
    document.addEventListener('visibilitychange', onVisibilityChange)
    return () => {
      window.clearInterval(interval)
      document.removeEventListener('visibilitychange', onVisibilityChange)
    }
  }, [applyProfile, session])

  useEffect(() => {
    if (!duelOpen) return
    let stopped = false
    const checkWatcher = async () => {
      try {
        const response = await fetch(`${WATCHER_BASE_URL}/health`)
        if (!stopped) setWatcherOnline(response.ok)
      } catch {
        if (!stopped) setWatcherOnline(false)
      }
    }
    void checkWatcher()
    const interval = window.setInterval(checkWatcher, 3000)
    return () => { stopped = true; window.clearInterval(interval) }
  }, [duelOpen])

  useEffect(() => {
    if (!lobby || !isBotDuel(lobby)) return
    let stopped = false
    const loadWatcherJob = async () => {
      try {
        const response = await watcherFetch('/v1/duels/status')
        if (!response.ok) throw new Error()
        const job = await response.json() as WatcherJob
        if (!stopped) {
          setWatcherOnline(true)
          setWatcherJob(job.matchId === lobby.matchId ? job : null)
        }
      } catch {
        if (!stopped) setWatcherOnline(false)
      }
    }
    void loadWatcherJob()
    const interval = window.setInterval(loadWatcherJob, 1500)
    return () => { stopped = true; window.clearInterval(interval) }
  }, [lobby])

  useEffect(() => {
    if (!lobby || !watcherJob?.outcome || watcherJob.matchId !== lobby.matchId) return
    if (sessionStorage.getItem(BOT_WATCHER_MATCH_KEY) !== lobby.matchId) return
    if (reportedWatcherMatchRef.current === lobby.matchId) return
    reportedWatcherMatchRef.current = lobby.matchId
    void recordLobbyResult(
      watcherJob.outcome === 'VICTORY',
      watcherJob.objective ?? undefined,
    ).then((recorded) => {
      if (!recorded) reportedWatcherMatchRef.current = null
    })
  }, [lobby, watcherJob])

  useEffect(() => {
    if (!queue && !match) return
    const interval = window.setInterval(() => setClock(Date.now()), 1000)
    return () => window.clearInterval(interval)
  }, [match, queue])

  const queueElapsed = useMemo(() => {
    void clock
    return queue ? formatElapsed(queue.joinedAt) : '—'
  }, [queue, clock])

  const readySeconds = useMemo(() => {
    void clock
    if (!match) return 0
    return Math.max(0, Math.ceil((new Date(match.readyDeadline).getTime() - Date.now()) / 1000))
  }, [clock, match])

  const currentPlayer = match?.players.find((player) => player.playerId === session?.playerId)
  const acceptedPlayers = match?.players.filter((player) => player.readyState === 'ACCEPTED').length ?? 0

  async function toggleQueue() {
    if (!session) {
      await beginLogin()
      return
    }
    if (!queue && party) {
      setPartyOpen(true)
      setPartyMessage(party.allReady
        ? 'Ton groupe est prêt : le chef peut lancer la recherche commune.'
        : 'Prépare ton groupe avant de lancer une recherche commune.')
      return
    }

    setBusy(true)
    setMessage(null)
    try {
      if (queue) {
        const response = await authenticatedFetch(
          queue.partyId ? '/api/v2/parties/current/search' : '/api/v2/matchmaking/queue',
          { method: 'DELETE' },
        )
        if (!response.ok) throw new Error('Impossible de quitter la file')
        setQueue(null)
        setMessage('Tu as quitté la file.')
      } else {
        const response = await authenticatedFetch('/api/v2/matchmaking/queue', {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'Idempotency-Key': crypto.randomUUID(),
          },
          body: JSON.stringify({ region, mode: selectedMode, primaryRole, secondaryRole }),
        })
        if (!response.ok) throw new Error('Impossible de rejoindre la file')
        setQueue(await response.json() as QueueEntry)
        setMessage('Recherche lancée. Bonne chance !')
      }
    } catch (error) {
      setMessage(error instanceof Error ? error.message : 'Action impossible')
    } finally {
      setBusy(false)
    }
  }

  async function disconnect() {
    try {
      await authenticatedFetch('/api/v2/players/me/presence', { method: 'DELETE' })
    } catch {
      // La déconnexion locale reste possible même si l'API est momentanément indisponible.
    } finally {
      await signOut()
      setSession(null)
      setQueue(null)
      setMatch(null)
      setLobby(null)
      sessionStorage.removeItem(BOT_WATCHER_MATCH_KEY)
      setProfile(null)
      setParty(null)
      setIncomingInvitations([])
      setDuelChallenges([])
      setDuelPlayerNames({})
      setWatcherJob(null)
      setDuelOpen(false)
      setProfileOpen(false)
      setPartyOpen(false)
      setHistoryOpen(false)
      setHistory([])
      setHistoryPage(null)
      setHistoryDetail(null)
      setStatistics(null)
      setMessage('Session fermée sur cet appareil.')
    }
  }

  function selectRole(slot: 'primary' | 'secondary', role: LaneRole) {
    if (queue) return
    if (slot === 'primary') {
      if (role === secondaryRole) setSecondaryRole(primaryRole)
      setPrimaryRole(role)
      return
    }
    if (role === primaryRole) setPrimaryRole(secondaryRole)
    setSecondaryRole(role)
  }

  function selectProfileRole(slot: 'primary' | 'secondary', role: LaneRole) {
    setProfileMessage(null)
    if (slot === 'primary') {
      if (role === profileSecondaryRole) setProfileSecondaryRole(profilePrimaryRole)
      setProfilePrimaryRole(role)
      return
    }
    if (role === profilePrimaryRole) setProfilePrimaryRole(profileSecondaryRole)
    setProfileSecondaryRole(role)
  }

  async function saveProfile() {
    setBusy(true)
    setProfileMessage(null)
    try {
      const response = await authenticatedFetch('/api/v2/players/me', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          displayName: profileName,
          region: profileRegion,
          primaryRole: profilePrimaryRole,
          secondaryRole: profileSecondaryRole,
        }),
      })
      if (response.status === 409) throw new Error('Ce pseudo est déjà utilisé.')
      if (response.status === 400) throw new Error('Vérifie le pseudo et les deux rôles choisis.')
      if (!response.ok) throw new Error('Impossible d’enregistrer le profil')
      applyProfile(await response.json() as PlayerProfile)
      setProfileMessage('Profil enregistré.')
    } catch (error) {
      setProfileMessage(error instanceof Error ? error.message : 'Enregistrement impossible')
    } finally {
      setBusy(false)
    }
  }

  async function createParty() {
    setBusy(true)
    setPartyMessage(null)
    try {
      const response = await authenticatedFetch('/api/v2/parties', { method: 'POST' })
      if (response.status === 409) throw new Error('Tu appartiens déjà à un groupe.')
      if (!response.ok) throw new Error('Impossible de créer le groupe')
      setParty(await response.json() as PartySnapshot)
      setPartyMessage('Groupe créé. Tu peux maintenant inviter tes alliés.')
    } catch (error) {
      setPartyMessage(error instanceof Error ? error.message : 'Création impossible')
    } finally {
      setBusy(false)
    }
  }

  async function invitePlayer() {
    if (!inviteName.trim()) return
    setBusy(true)
    setPartyMessage(null)
    try {
      const response = await authenticatedFetch('/api/v2/parties/current/invitations', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ displayName: inviteName }),
      })
      if (response.status === 409) throw new Error('Ce joueur est déjà invité ou membre d’un groupe.')
      if (!response.ok) throw new Error('Invitation impossible')
      setParty(await response.json() as PartySnapshot)
      setPartyMessage(`Invitation envoyée à ${inviteName.trim()}.`)
      setInviteName('')
    } catch (error) {
      setPartyMessage(error instanceof Error ? error.message : 'Invitation impossible')
    } finally {
      setBusy(false)
    }
  }

  async function changePartyRegion(nextRegion: Region) {
    setBusy(true)
    setPartyMessage(null)
    try {
      const response = await authenticatedFetch('/api/v2/parties/current', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ region: nextRegion }),
      })
      if (!response.ok) throw new Error('Impossible de changer la région')
      setParty(await response.json() as PartySnapshot)
      setPartyMessage('Région modifiée. Les joueurs doivent confirmer à nouveau.')
    } catch (error) {
      setPartyMessage(error instanceof Error ? error.message : 'Action impossible')
    } finally {
      setBusy(false)
    }
  }

  async function setPartyReady(member: PartyMember, ready: boolean) {
    setBusy(true)
    setPartyMessage(null)
    try {
      const path = member.simulated
        ? `/api/v2/parties/current/members/${member.playerId}/ready`
        : '/api/v2/parties/current/ready'
      const response = await authenticatedFetch(path, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ ready }),
      })
      if (!response.ok) throw new Error('Impossible de modifier l’état prêt')
      setParty(await response.json() as PartySnapshot)
    } catch (error) {
      setPartyMessage(error instanceof Error ? error.message : 'Action impossible')
    } finally {
      setBusy(false)
    }
  }

  async function removePartyMember(member: PartyMember) {
    setBusy(true)
    setPartyMessage(null)
    try {
      const response = await authenticatedFetch(`/api/v2/parties/current/members/${member.playerId}`, {
        method: 'DELETE',
      })
      if (!response.ok) throw new Error('Impossible de retirer ce joueur')
      setParty(await response.json() as PartySnapshot)
      setPartyMessage(`${member.displayName} a quitté le groupe.`)
    } catch (error) {
      setPartyMessage(error instanceof Error ? error.message : 'Action impossible')
    } finally {
      setBusy(false)
    }
  }

  async function leaveParty() {
    setBusy(true)
    setPartyMessage(null)
    try {
      const response = await authenticatedFetch('/api/v2/parties/current', { method: 'DELETE' })
      if (!response.ok) throw new Error('Impossible de quitter le groupe')
      setParty(null)
      setQueue(null)
      setPartyMessage('Groupe fermé.')
    } catch (error) {
      setPartyMessage(error instanceof Error ? error.message : 'Action impossible')
    } finally {
      setBusy(false)
    }
  }

  async function answerPartyInvitation(invitationId: string, accepted: boolean) {
    setBusy(true)
    setPartyMessage(null)
    try {
      const response = await authenticatedFetch(`/api/v2/parties/invitations/${invitationId}/${accepted ? 'accept' : 'decline'}`, {
        method: 'POST',
      })
      if (!response.ok) throw new Error('Cette invitation n’est plus disponible')
      if (accepted) setParty(await response.json() as PartySnapshot)
      await loadPartyInvitations()
      setPartyMessage(accepted ? 'Tu as rejoint le groupe.' : 'Invitation refusée.')
    } catch (error) {
      setPartyMessage(error instanceof Error ? error.message : 'Réponse impossible')
    } finally {
      setBusy(false)
    }
  }

  async function startPartySearch() {
    if (!party) return
    setBusy(true)
    setPartyMessage(null)
    try {
      const response = await authenticatedFetch('/api/v2/parties/current/search', {
        method: 'POST',
        headers: { 'Idempotency-Key': crypto.randomUUID() },
      })
      if (response.status === 409) throw new Error('Tous les joueurs doivent être prêts et hors d’une autre file.')
      if (!response.ok) throw new Error('Impossible de lancer la recherche commune')
      setParty(await response.json() as PartySnapshot)
      await loadQueue()
      setPartyOpen(false)
      setMessage(`Recherche commune lancée pour ${party.members.length} joueurs.`)
    } catch (error) {
      setPartyMessage(error instanceof Error ? error.message : 'Recherche impossible')
    } finally {
      setBusy(false)
    }
  }

  async function answerReady(accepted: boolean) {
    if (!match) return
    setBusy(true)
    setMessage(null)
    try {
      const response = await authenticatedFetch(`/api/v2/matches/${match.matchId}/ready`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ accepted }),
      })
      if (!response.ok) throw new Error('Réponse au ready check refusée')
      const updated = await response.json() as CurrentMatch
      if (accepted) {
        if (updated.status === 'CONFIRMED') {
          setLobby(updated)
          setMatch(null)
          setQueue(null)
          if (isBotDuel(updated)) {
            try {
              await requestBotWatcher(updated)
              setMessage('Match confirmé. Le watcher crée le lobby League et ajoute le bot.')
            } catch (error) {
              setMessage(error instanceof Error ? error.message : 'Le lobby web est prêt, mais le watcher n’a pas démarré.')
            }
            return
          }
        } else {
          setMatch(updated)
        }
      } else {
        setMatch(null)
        setQueue(null)
      }
      setMessage(accepted
        ? updated.status === 'CONFIRMED'
          ? 'Match confirmé. Le lobby web est prêt.'
          : 'Tu es prêt. En attente des autres joueurs…'
        : 'Match refusé.')
    } catch (error) {
      setMessage(error instanceof Error ? error.message : 'Réponse impossible')
    } finally {
      setBusy(false)
    }
  }

  async function recordLobbyResult(won: boolean, automaticObjective?: WatcherJob['objective']) {
    if (!lobby || !isBotDuel(lobby) || !automaticObjective) return false
    const player = lobby.players.find((candidate) => candidate.playerId === session?.playerId)
    if (!player) return false
    const winningTeam = won ? player.team : player.team === 'BLUE' ? 'RED' : 'BLUE'
    setBusy(true)
    setMessage(null)
    try {
      const response = await authenticatedFetch(`/api/v2/matches/${lobby.matchId}/bot-result`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ winningTeam }),
      })
      if (response.status === 403) throw new Error('Reconnecte-toi une fois pour activer les résultats et le MMR.')
      if (!response.ok) throw new Error('Impossible d’enregistrer ce résultat')
      setLobby(null)
      sessionStorage.removeItem(BOT_WATCHER_MATCH_KEY)
      await loadHistory()
      setHistoryOpen(true)
      const objectiveLabel = automaticObjective === 'FIRST_BLOOD'
        ? 'First Blood'
        : automaticObjective === 'FIRST_TOWER'
          ? 'première tour'
          : automaticObjective === 'FIRST_TO_100_CS'
            ? '100 CS'
            : null
      setMessage(automaticObjective
        ? `${won ? 'Victoire' : 'Défaite'} détectée automatiquement${objectiveLabel ? ` · ${objectiveLabel}` : ''}. Historique mis à jour.`
        : won ? 'Victoire enregistrée. Ton MMR a été mis à jour.' : 'Défaite enregistrée. Ton MMR a été mis à jour.')
      return true
    } catch (error) {
      setMessage(error instanceof Error ? error.message : 'Action impossible')
      return false
    } finally {
      setBusy(false)
    }
  }

  async function openHistory() {
    if (!session) {
      await beginLogin()
      return
    }
    setHistoryOpen(true)
    setHistoryMessage(null)
    try {
      await loadHistory()
    } catch (error) {
      setHistoryMessage(error instanceof Error ? error.message : 'Chargement impossible')
    }
  }

  function displayPlayer(player: MatchPlayer) {
    if (player.playerId === session?.playerId) return profile?.displayName ?? session.username
    return player.bot ? `Bot ${player.playerId.slice(0, 4)}` : `Joueur ${player.playerId.slice(0, 6)}`
  }

  function orderedTeam(source: CurrentMatch, team: MatchPlayer['team']) {
    return source.players
      .filter((player) => player.team === team)
      .sort((left, right) => laneRoles.indexOf(left.assignedRole) - laneRoles.indexOf(right.assignedRole))
  }

  async function copyCredential(kind: 'name' | 'password', value: string | null) {
    if (!value) return
    try {
      await navigator.clipboard.writeText(value)
      setCopiedCredential(kind)
      window.setTimeout(() => setCopiedCredential((current) => current === kind ? null : current), 1800)
    } catch {
      setMessage('La copie automatique est indisponible dans ce navigateur.')
    }
  }

  async function linkRiotAccount() {
    setBusy(true)
    setProfileMessage(null)
    try {
      const challengeResponse = await authenticatedFetch('/api/v2/players/me/riot-link-challenges', {
        method: 'POST',
      })
      if (!challengeResponse.ok) throw new Error('Impossible de créer le challenge de liaison Riot.')
      const challenge = await challengeResponse.json() as RiotLinkChallenge
      const watcherResponse = await watcherFetch('/v1/identity')
      if (!watcherResponse.ok) throw new Error('Lance le watcher et ouvre le client League avant de réessayer.')
      const identity = await watcherResponse.json() as WatcherIdentity
      const response = await authenticatedFetch(`/api/v2/players/me/riot-link-challenges/${challenge.challengeId}/complete`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          puuid: identity.puuid,
          gameName: identity.gameName,
          tagLine: identity.tagLine,
          profileIconId: identity.profileIconId,
          summonerLevel: identity.summonerLevel,
        }),
      })
      if (response.status === 409) throw new Error('Ce compte Riot est déjà lié à un autre profil Showdown.')
      if (!response.ok) throw new Error('La liaison Riot a été refusée.')
      applyProfile(await response.json() as PlayerProfile)
      setWatcherOnline(true)
      setProfileMessage(`${identity.riotId} a été détecté localement et lié à ce profil.`)
    } catch (error) {
      setWatcherOnline(false)
      setProfileMessage(error instanceof Error ? error.message : 'Watcher indisponible')
    } finally {
      setBusy(false)
    }
  }

  async function openDuels() {
    if (!session) { await beginLogin(); return }
    setDuelOpen(true)
    setDuelMessage(null)
    try {
      const health = await fetch(`${WATCHER_BASE_URL}/health`)
      setWatcherOnline(health.ok)
      await loadDuels()
    } catch {
      setWatcherOnline(false)
      await loadDuels().catch(() => undefined)
    }
  }

  async function createDuel() {
    if (!profile?.riotId) { setDuelMessage('Lie d’abord ton compte Riot depuis ton profil.'); return }
    setBusy(true)
    setDuelMessage(null)
    try {
      const lookup = await authenticatedFetch(`/api/v2/players/directory/search?displayName=${encodeURIComponent(duelOpponentName)}`)
      if (lookup.status === 404) throw new Error('Ce pseudo Showdown est introuvable.')
      if (!lookup.ok) throw new Error('Recherche du joueur impossible.')
      const opponent = await lookup.json() as DirectoryPlayer
      if (!opponent.riotLinked) throw new Error('Ce joueur doit d’abord lier son compte Riot.')
      const response = await authenticatedFetch('/api/v2/matches/duels', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ opponentId: opponent.playerId }),
      })
      if (!response.ok) throw new Error(response.status === 409 ? 'Un des deux joueurs a déjà une invitation ou un match actif.' : 'Invitation impossible.')
      await loadDuels()
      setDuelMessage(`Invitation directe envoyée à ${opponent.displayName}.`)
    } catch (error) {
      setDuelMessage(error instanceof Error ? error.message : 'Invitation impossible')
    } finally { setBusy(false) }
  }

  async function answerDuel(challengeId: string, action: 'accept' | 'decline' | 'cancel') {
    setBusy(true)
    setDuelMessage(null)
    try {
      const response = await authenticatedFetch(`/api/v2/matches/duels/${challengeId}/${action}`, { method: 'POST' })
      if (!response.ok) throw new Error('Cette invitation n’est plus disponible.')
      await loadDuels()
      await loadLobby()
      setDuelMessage(action === 'accept' ? 'Duel accepté. Lance maintenant ton watcher.' : action === 'decline' ? 'Invitation refusée.' : 'Invitation annulée.')
    } catch (error) { setDuelMessage(error instanceof Error ? error.message : 'Action impossible') }
    finally { setBusy(false) }
  }

  async function startWatcher(challenge: DuelChallenge) {
    if (!challenge.matchId) return
    setBusy(true)
    setDuelMessage(null)
    try {
      const response = await authenticatedFetch(`/api/v2/matches/duels/${challenge.matchId}/watcher-token`, { method: 'POST' })
      if (!response.ok) {
        if (response.status === 409) throw new Error('Ce duel est déjà terminé ou son lobby n’est plus actif. Crée une nouvelle invitation.')
        throw new Error('Impossible de créer le jeton temporaire du watcher.')
      }
      const issued = await response.json() as WatcherToken
      const watcher = await watcherFetch('/v1/duels/start', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          serverBaseUrl: window.location.port === '3000' ? 'http://localhost:8088' : window.location.origin,
          matchId: issued.matchId,
          watcherToken: issued.token,
        }),
      })
      if (!watcher.ok) throw new Error('Le watcher local est arrêté ou suit déjà un duel.')
      setWatcherOnline(true)
      setDuelMessage(issued.role === 'HOST'
        ? 'Watcher hôte lancé : il crée le lobby, invite l’adversaire et attend les deux joueurs.'
        : 'Watcher invité lancé : il tente de rejoindre le lobby puis accepte l’invitation si nécessaire.')
    } catch (error) {
      setWatcherOnline(false)
      setDuelMessage(error instanceof Error ? error.message : 'Démarrage impossible')
    } finally { setBusy(false) }
  }

  function isBotDuel(value: CurrentMatch) {
    return value.mode === 'ONE_V_ONE' && value.players.some(player => player.bot)
  }

  function watcherStateLabel(state: string | undefined) {
    if (!state) return watcherOnline ? 'Détecté' : 'À lancer'
    if (state === 'REVIEW_REQUIRED') return 'Revue requise'
    if (state === 'ERROR') return 'Erreur'
    return state
  }

  async function requestBotWatcher(value: CurrentMatch) {
    if (!value.lobbyName || !value.lobbyPassword) throw new Error('Les identifiants du lobby League sont indisponibles.')
    const watcher = await watcherFetch('/v1/bot-duels/start', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        matchId: value.matchId,
        lobbyName: value.lobbyName,
        lobbyPassword: value.lobbyPassword,
      }),
    })
    if (!watcher.ok) {
      const failure = await watcher.json().catch(() => null) as { error?: string } | null
      throw new Error(failure?.error
        ? `Lobby web prêt, mais watcher : ${failure.error}`
        : 'Lobby web prêt, mais le watcher doit être redémarré et le client League ouvert.')
    }
    const job = await watcher.json() as WatcherJob
    sessionStorage.setItem(BOT_WATCHER_MATCH_KEY, value.matchId)
    setWatcherOnline(true)
    setWatcherJob(job)
    return job
  }

  async function retryBotWatcher() {
    if (!lobby || !isBotDuel(lobby)) return
    setBusy(true)
    try {
      await requestBotWatcher(lobby)
      setMessage('Watcher relancé : création du lobby League et ajout du bot en cours.')
    } catch (error) {
      setMessage(error instanceof Error ? error.message : 'Impossible de relancer le watcher.')
    } finally {
      setBusy(false)
    }
  }

  const actionLabel = busy
    ? 'Un instant…'
    : queue
      ? queue.status === 'RESERVED' ? 'Partie en préparation' : 'Quitter la file'
      : session ? 'Trouver une partie' : 'Se connecter pour jouer'
  const playerDisplayName = profile?.displayName ?? session?.username ?? 'Joueur'

  return (
    <div className="app-shell">
      <header className="topbar">
        <a className="brand" href="#top" aria-label="Accueil Showdown">
          <span className="brand-mark"><b>S</b></span>
          <span>SHOWDOWN</span>
        </a>
        <nav aria-label="Navigation principale">
          <button className="nav-item active">Jouer</button>
          <button className="nav-item" onClick={() => session ? setProfileOpen(true) : void beginLogin()}>Profil</button>
          <button className="nav-item team-nav" onClick={() => session ? setPartyOpen(true) : void beginLogin()}>
            Équipe
            {(party || incomingInvitations.length > 0) && <small>{incomingInvitations.length > 0 ? `${incomingInvitations.length} invitation` : `${party?.members.length ?? 0}/5`}</small>}
          </button>
          <button className={historyOpen ? 'nav-item active' : 'nav-item'} onClick={() => void openHistory()}>
            Historique
            {statistics && <small>{statistics.mmr} MMR</small>}
          </button>
          <button className={leaderboardOpen ? 'nav-item active' : 'nav-item'} onClick={() => {
            if (!session) { void beginLogin(); return }
            setLeaderboardOpen(true)
            void loadLeaderboard().catch(error => setMessage(error instanceof Error ? error.message : 'Classement indisponible'))
          }}>Classement</button>
        </nav>
        <button className="profile-button" onClick={() => session ? setProfileOpen(true) : void beginLogin()}>
          <span className={session ? 'presence-dot' : 'presence-dot offline'} />
          {session ? playerDisplayName : 'Se connecter'}
        </button>
      </header>

      <main id="top">
        <section className="hero-copy" aria-labelledby="page-title">
          <p className="kicker">SAISON 2026 · FILE CLASSÉE</p>
          <h1 id="page-title">Ton prochain<br /><em>showdown</em> commence ici.</h1>
          <p className="hero-description">
            Rejoins une équipe équilibrée, retrouve tes rôles favoris et entre dans la Faille avec des joueurs qui veulent gagner ensemble.
          </p>
          {session && (
            <div className="player-chip">
              <span className="player-avatar">{playerDisplayName.slice(0, 1).toUpperCase()}</span>
              <span><small>CONNECTÉ EN TANT QUE</small><strong>{playerDisplayName}</strong></span>
            </div>
          )}
        </section>

        <section className={queue ? 'queue-panel searching' : 'queue-panel'} aria-label="Lancer une recherche de partie">
          <div className="panel-heading">
            <div>
              <p className="micro-label">{queue ? 'RECHERCHE EN COURS' : 'MODE SÉLECTIONNÉ'}</p>
              <h2>{queue ? `${queue.mode === 'ONE_V_ONE' ? 'Duel de test' : 'File'} ${queue.region} · ${queue.mmr} MMR` : selectedMode === 'ONE_V_ONE' ? 'Duel 1 contre 1' : 'Faille de l’invocateur'}</h2>
            </div>
            <span className={online && realtimeConnected ? 'live-pill' : 'live-pill offline'}><i /> {online && realtimeConnected ? 'TEMPS RÉEL ACTIF' : online ? 'CONNEXION TEMPS RÉEL…' : 'RÉSEAU INDISPONIBLE'}</span>
          </div>

          <div className="mode-grid">
            <button className={selectedMode === 'FIVE_V_FIVE' ? 'mode-card selected' : 'mode-card'} disabled={Boolean(queue)} onClick={() => setSelectedMode('FIVE_V_FIVE')}>
              <span className="mode-icon">5<span>v</span>5</span>
              <strong>Équipe complète</strong>
              <small>Match équilibré · 25–35 min</small>
            </button>
            <button className={selectedMode === 'ONE_V_ONE' ? 'mode-card selected' : 'mode-card'} disabled={Boolean(queue)} onClick={() => setSelectedMode('ONE_V_ONE')}>
              <span className="mode-icon">1<span>v</span>1</span>
              <strong>Duel</strong>
              <small>Test local · bot après 5 s</small>
            </button>
          </div>

          {selectedMode === 'ONE_V_ONE' && !queue && (
            <button className="duel-direct-link" onClick={() => void openDuels()}>Ou défier directement un joueur réel →</button>
          )}

          <div className="role-row">
            <div>
              <p className="micro-label">RÉGION</p>
              <strong>{queue ? queue.region : region}</strong>
            </div>
            <div className="role-tokens" aria-label="Choix de la région">
              {(['EUW', 'EUNE', 'NA'] as Region[]).map((candidate) => (
                <button
                  className={candidate === region ? 'selected' : ''}
                  disabled={Boolean(queue)}
                  key={candidate}
                  onClick={() => setRegion(candidate)}
                >
                  {candidate}
                </button>
              ))}
            </div>
          </div>

          {selectedMode === 'FIVE_V_FIVE' ? <div className="role-picker" aria-label="Préférences de rôle">
            <div className="role-picker-heading">
              <div>
                <p className="micro-label">TES RÔLES</p>
                <strong>Choisis deux positions</strong>
              </div>
              <small>Le serveur attribue un rôle unique à chaque joueur.</small>
            </div>
            <div className="role-matrix">
              <div className="role-matrix-label" />
              {laneRoles.map((role) => <span className="role-name" key={role}>{laneLabels[role]}</span>)}
              <span className="role-matrix-label primary-label">Principal</span>
              {laneRoles.map((role) => (
                <button
                  className={primaryRole === role ? 'role-choice selected primary' : 'role-choice'}
                  disabled={Boolean(queue)}
                  key={`primary-${role}`}
                  onClick={() => selectRole('primary', role)}
                  aria-label={`${laneLabels[role]} en rôle principal`}
                >
                  <span>{laneMarks[role]}</span>
                </button>
              ))}
              <span className="role-matrix-label secondary-label">Secondaire</span>
              {laneRoles.map((role) => (
                <button
                  className={secondaryRole === role ? 'role-choice selected secondary' : 'role-choice'}
                  disabled={Boolean(queue)}
                  key={`secondary-${role}`}
                  onClick={() => selectRole('secondary', role)}
                  aria-label={`${laneLabels[role]} en rôle secondaire`}
                >
                  <span>{laneMarks[role]}</span>
                </button>
              ))}
            </div>
          </div> : <div className="duel-test-note"><strong>Duel de test classé</strong><small>Après 5 secondes seul dans la file EUW, un bot à 1500 MMR complète automatiquement le match. Le résultat modifie ton MMR Glicko-2.</small></div>}

          {queue && <div className="queue-timer"><span>{queueElapsed}</span><small>temps dans la file</small></div>}
          <button className={queue ? 'primary-action leave-action' : 'primary-action'} disabled={busy || queue?.status === 'RESERVED' || online === false} onClick={toggleQueue}>
            <span>{actionLabel}</span>
            <span aria-hidden="true">{queue ? '×' : '→'}</span>
          </button>
          <p className="action-note">{session ? `Identité joueur · ${session.playerId.slice(0, 8)}` : 'Connexion OAuth2 avec PKCE · aucun mot de passe stocké dans le navigateur'}</p>
          {message && <p className="feedback" role="status">{message}</p>}
        </section>

        <section className="lower-grid" aria-label="Informations de jeu">
          <article className="stat-card">
            <p className="micro-label">ÉTAT DU RÉSEAU</p>
            <div className="status-line"><span className={online ? 'presence-dot' : 'presence-dot offline'} /><strong>{online === null ? 'Vérification…' : online ? 'Opérationnel' : 'Indisponible'}</strong></div>
            <p>Gateway, identité et matchmaking sont surveillés en temps réel.</p>
          </article>
          <article className="stat-card accent-card">
            <p className="micro-label">TON GROUPE</p>
            <strong>{party ? `${party.members.length}/5 joueurs réunis` : 'Joue avec tes alliés'}</strong>
            <p>{party ? `${party.members.filter((member) => member.ready).length} joueurs prêts pour une recherche commune.` : 'Crée un groupe, invite tes alliés et lancez la recherche ensemble.'}</p>
            <button onClick={() => session ? setPartyOpen(true) : void beginLogin()}>{party ? 'Ouvrir le groupe' : 'Créer une équipe'} <span>→</span></button>
          </article>
          <article className="stat-card">
            <p className="micro-label">FILE {queue?.region ?? region}</p>
            <div className="big-stat">{queueElapsed}{queue ? '' : <small> min</small>}</div>
            <p>{queue ? `Recherche active pour une partie ${queue.mode === 'ONE_V_ONE' ? '1v1' : '5v5'}.` : 'Lance une recherche pour démarrer le chronomètre.'}</p>
          </article>
        </section>
      </main>

      {historyOpen && session && (
        <div className="history-screen" role="dialog" aria-modal="true" aria-labelledby="history-title">
          <section className="history-shell">
            <header className="history-heading">
              <div>
                <p className="kicker">{statistics?.season ?? 'SAISON'} · {statistics?.region ?? profileRegionRef.current}</p>
                <h2 id="history-title">Ton historique</h2>
                <p>Résultats, statistiques par rôle et progression TrueSkill calculés par le serveur.</p>
              </div>
              <button className="profile-close" onClick={() => setHistoryOpen(false)} aria-label="Fermer l’historique">×</button>
            </header>

            {statistics && (
              <div className="history-summary">
                <article className="mmr-card">
                  <span className="rank-emblem">{statistics.rank.slice(0, 1)}</span>
                  <span><small>TRUESKILL 5V5</small><strong>{statistics.rank}</strong><b>{statistics.mmr} MMR</b></span>
                  <em>μ {statistics.skillMean.toFixed(2)} · σ {statistics.skillDeviation.toFixed(2)} · Pic {statistics.peakMmr}</em>
                </article>
                <article><small>{statistics.placementGamesRemaining > 0 ? 'PLACEMENTS RESTANTS' : 'PROGRESSION'}</small><strong>{statistics.placementGamesRemaining > 0 ? statistics.placementGamesRemaining : `${statistics.progression >= 0 ? '+' : ''}${statistics.progression}`}</strong><span>{statistics.wins} V · {statistics.losses} D</span></article>
                <article><small>TAUX DE VICTOIRE</small><strong>{statistics.winRate.toFixed(1)}%</strong><span>{statistics.games === 0 ? 'Joue ton premier match' : 'Toutes parties classées'}</span></article>
                <article className="recent-form"><small>FORME RÉCENTE</small><div>{statistics.recentForm.length === 0 ? <span>—</span> : statistics.recentForm.slice(0, 5).map((outcome, index) => <i className={outcome.toLowerCase()} key={`${outcome}-${index}`}>{outcome === 'VICTORY' ? 'V' : 'D'}</i>)}</div><span>5 dernières parties</span></article>
              </div>
            )}

            {duelStatistics && (
              <article className="duel-rating-card">
                <span className="duel-mark">1V1</span>
                <span><small>GLICKO-2 · CLASSEMENT DUEL</small><strong>{duelStatistics.mmr} MMR</strong><b>{duelStatistics.provisional ? 'Cote provisoire' : `${duelStatistics.progression >= 0 ? '+' : ''}${duelStatistics.progression} cette saison`}</b></span>
                <span><small>INCERTITUDE</small><strong>RD {duelStatistics.ratingDeviation.toFixed(1)}</strong><b>σ {duelStatistics.volatility.toFixed(5)}</b></span>
                <span><small>DUELS</small><strong>{duelStatistics.wins} V · {duelStatistics.losses} D</strong><b>{duelStatistics.winRate.toFixed(1)}% · Pic {duelStatistics.peakMmr}</b></span>
                <em>{duelStatistics.placementGamesRemaining > 0 ? `${duelStatistics.placementGamesRemaining} placement${duelStatistics.placementGamesRemaining > 1 ? 's' : ''} restant${duelStatistics.placementGamesRemaining > 1 ? 's' : ''}` : 'Classé'}</em>
              </article>
            )}

            <div className="history-layout">
              <section className="match-history-card">
                <header><span><small>PARTIES TERMINÉES</small><strong>Résultats récents</strong></span><b>{historyPage?.totalElements ?? history.length} résultat{(historyPage?.totalElements ?? history.length) > 1 ? 's' : ''}</b></header>
                <div className="history-filters">
                  <label>Région<select value={historyQuery.region} onChange={event => void updateHistoryQuery({ region: event.target.value as HistoryQuery['region'], page: 0 })}><option value="">Toutes</option><option value="EUW">EUW</option><option value="EUNE">EUNE</option><option value="NA">NA</option></select></label>
                  <label>Rôle<select value={historyQuery.role} onChange={event => void updateHistoryQuery({ role: event.target.value as HistoryQuery['role'], page: 0 })}><option value="">Tous</option>{laneRoles.map(role => <option value={role} key={role}>{laneLabels[role]}</option>)}</select></label>
                  <label>Résultat<select value={historyQuery.outcome} onChange={event => void updateHistoryQuery({ outcome: event.target.value as HistoryQuery['outcome'], page: 0 })}><option value="">Tous</option><option value="VICTORY">Victoires</option><option value="DEFEAT">Défaites</option></select></label>
                </div>
                {history.length === 0 ? (
                  <div className="history-empty"><span>⌁</span><strong>Aucune partie terminée</strong><p>Ton premier résultat apparaîtra ici avec son évolution MMR.</p></div>
                ) : history.map((entry) => (
                  <button className={`history-match ${entry.outcome.toLowerCase()}`} key={entry.matchId} onClick={() => void openMatchDetail(entry.matchId)}>
                    <span className="history-outcome"><i>{entry.outcome === 'VICTORY' ? 'V' : 'D'}</i><b>{entry.outcome === 'VICTORY' ? 'Victoire' : 'Défaite'}</b><small>{new Intl.DateTimeFormat('fr-FR', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(entry.playedAt))}</small></span>
                    <span className={`lane-emblem ${entry.role.toLowerCase()}`}>{laneMarks[entry.role]}</span>
                    <span className="history-context"><strong>{entry.mode === 'ONE_V_ONE' ? 'Duel classé' : `${laneLabels[entry.role]} · Équipe ${entry.team === 'BLUE' ? 'bleue' : 'rouge'}`}</strong><small>{entry.region} · {entry.mode === 'ONE_V_ONE' ? '1 contre 1 · Glicko-2' : '5 contre 5 · TrueSkill'} · #{entry.matchId.slice(0, 8).toUpperCase()}</small></span>
                    <span className={entry.mmrDelta > 0 ? 'history-mmr positive' : 'history-mmr negative'}><b>{entry.mmrDelta > 0 ? '+' : ''}{entry.mmrDelta}</b><small>{entry.newMmr} MMR</small></span>
                  </button>
                ))}
                {historyPage && historyPage.totalPages > 1 && <div className="history-pagination"><button disabled={!historyPage.hasPrevious} onClick={() => void updateHistoryQuery({ page: historyPage.page - 1 })}>← Précédent</button><span>Page {historyPage.page + 1} / {historyPage.totalPages}</span><button disabled={!historyPage.hasNext} onClick={() => void updateHistoryQuery({ page: historyPage.page + 1 })}>Suivant →</button></div>}
              </section>

              <aside className="role-statistics-card">
                <p className="micro-label">PARTIES PAR RÔLE</p>
                <h3>Tes positions</h3>
                {laneRoles.map((role) => {
                  const count = statistics?.gamesByRole[role] ?? 0
                  const total = Math.max(1, statistics?.games ?? 0)
                  return <div className="role-stat-row" key={role}><span className={`lane-emblem ${role.toLowerCase()}`}>{laneMarks[role]}</span><span><strong>{laneLabels[role]}</strong><i><b style={{ width: `${count / total * 100}%` }} /></i></span><em>{count}</em></div>
                })}
                <small>Les kills, morts et assists seront ajoutés lorsque le companion pourra remonter les données de partie.</small>
              </aside>
            </div>
            {historyMessage && <p className="profile-feedback" role="status">{historyMessage}</p>}
          </section>
          {historyDetail && <section className="match-detail-panel" role="dialog" aria-modal="true" aria-labelledby="match-detail-title">
            <header><span><small>FICHE DU MATCH · #{historyDetail.summary.matchId.slice(0, 8).toUpperCase()}</small><h3 id="match-detail-title">{historyDetail.summary.outcome === 'VICTORY' ? 'Victoire' : 'Défaite'} · {laneLabels[historyDetail.summary.role]}</h3><p>{historyDetail.summary.region} · {new Intl.DateTimeFormat('fr-FR', { dateStyle: 'long', timeStyle: 'short' }).format(new Date(historyDetail.summary.playedAt))}</p></span><button onClick={() => setHistoryDetail(null)} aria-label="Fermer la fiche du match">×</button></header>
            <div className="match-detail-score"><span><small>MMR AVANT</small><strong>{historyDetail.summary.previousMmr}</strong></span><b className={historyDetail.summary.mmrDelta >= 0 ? 'positive' : 'negative'}>{historyDetail.summary.mmrDelta >= 0 ? '+' : ''}{historyDetail.summary.mmrDelta}</b><span><small>MMR APRÈS</small><strong>{historyDetail.summary.newMmr}</strong></span></div>
            <div className="match-detail-teams">{([{ title: 'Coéquipiers', players: historyDetail.teammates }, { title: 'Adversaires', players: historyDetail.opponents }] as const).map(group => <section key={group.title}><h4>{group.title}</h4>{group.players.map(player => <article className={player.self ? 'self' : ''} key={player.playerId}><span className={`lane-emblem ${player.role.toLowerCase()}`}>{laneMarks[player.role]}</span><span><strong>{player.self ? profile?.displayName ?? 'Toi' : player.bot ? `Bot ${player.playerId.slice(0, 4)}` : historyNames[player.playerId] ?? `Joueur ${player.playerId.slice(0, 6)}`}</strong><small>{laneLabels[player.role]} · Équipe {player.team === 'BLUE' ? 'bleue' : 'rouge'}</small></span>{player.self && <em>TOI</em>}</article>)}</section>)}</div>
            <p className="match-detail-note">Le KDA sera ajouté avec le companion Tauri 2 ou les données Riot. Le navigateur ne tente pas de le reconstituer.</p>
          </section>}
        </div>
      )}

      {leaderboardOpen && session && (
        <div className="history-screen" role="dialog" aria-modal="true" aria-labelledby="leaderboard-title">
          <section className="history-shell leaderboard-shell">
            <header className="history-heading">
              <div><p className="kicker">{leaderboard?.season ?? 'SAISON'} · {leaderboard?.region ?? profileRegionRef.current}</p><h2 id="leaderboard-title">Classements régionaux</h2><p>Le 5v5 utilise TrueSkill ; le duel 1v1 possède un classement Glicko-2 indépendant.</p></div>
              <button className="profile-close" onClick={() => setLeaderboardOpen(false)} aria-label="Fermer le classement">×</button>
            </header>
            <h3 className="leaderboard-section-title">5V5 · TRUESKILL</h3>
            <div className="leaderboard-table">
              <header><span>#</span><span>JOUEUR</span><span>RANG</span><span>V / PARTIES</span><span>WIN RATE</span><span>MMR</span></header>
              {leaderboard?.entries.length ? leaderboard.entries.map(entry => (
                <article className={entry.playerId === session.playerId ? 'is-me' : ''} key={entry.playerId}>
                  <b>{entry.position}</b><span><strong>{leaderboardNames[entry.playerId] ?? `Joueur ${entry.playerId.slice(0, 6)}`}</strong><small>{entry.progression >= 0 ? '+' : ''}{entry.progression} cette saison</small></span><em>{entry.rank}</em><span>{entry.wins} / {entry.games}</span><span>{entry.winRate.toFixed(1)}%</span><strong>{entry.mmr}</strong>
                </article>
              )) : <div className="history-empty"><span>◇</span><strong>Aucun joueur placé</strong><p>Il faut terminer {leaderboard?.placementGames ?? 5} parties pour entrer au classement.</p></div>}
            </div>
            <h3 className="leaderboard-section-title">1V1 · GLICKO-2</h3>
            <div className="leaderboard-table duel-leaderboard-table">
              <header><span>#</span><span>JOUEUR</span><span>STATUT</span><span>V / DUELS</span><span>RD</span><span>MMR</span></header>
              {duelLeaderboard?.entries.length ? duelLeaderboard.entries.map(entry => (
                <article className={entry.playerId === session.playerId ? 'is-me' : ''} key={entry.playerId}>
                  <b>{entry.position}</b><span><strong>{leaderboardNames[entry.playerId] ?? `Joueur ${entry.playerId.slice(0, 6)}`}</strong><small>{entry.progression >= 0 ? '+' : ''}{entry.progression} cette saison</small></span><em>{entry.provisional ? 'PROVISOIRE' : 'CLASSÉ'}</em><span>{entry.wins} / {entry.games}</span><span>{entry.ratingDeviation.toFixed(1)}</span><strong>{entry.mmr}</strong>
                </article>
              )) : <div className="history-empty"><span>◇</span><strong>Aucun duelliste placé</strong><p>Il faut terminer {duelLeaderboard?.placementGames ?? 5} duels pour entrer au classement 1v1.</p></div>}
            </div>
          </section>
        </div>
      )}

      {partyOpen && session && (
        <div className="team-screen" role="dialog" aria-modal="true" aria-labelledby="team-title">
          <section className="team-shell">
            <header className="team-heading">
              <div>
                <p className="kicker">GROUPE CLASSÉ · 5V5</p>
                <h2 id="team-title">Ton équipe</h2>
                <p>Réunis tes alliés, confirmez vos rôles puis entrez ensemble dans la file.</p>
              </div>
              <button className="profile-close" onClick={() => setPartyOpen(false)} aria-label="Fermer l’équipe">×</button>
            </header>

            {!party ? (
              <div className="team-empty-layout">
                <section className="team-empty-card">
                  <span className="team-empty-mark">5</span>
                  <p className="micro-label">NOUVEAU GROUPE</p>
                  <h3>Plus forts ensemble.</h3>
                  <p>Crée ton groupe. Tu en seras le chef et ta région favorite sera utilisée pour la recherche.</p>
                  <button disabled={busy} onClick={() => void createParty()}>{busy ? 'Création…' : 'Créer mon équipe'}</button>
                </section>

                <aside className="incoming-invitations">
                  <p className="micro-label">INVITATIONS REÇUES</p>
                  {incomingInvitations.length === 0 ? (
                    <div className="no-invitation"><strong>Aucune invitation</strong><span>Les invitations de tes alliés apparaîtront ici.</span></div>
                  ) : incomingInvitations.map((invitation) => (
                    <article key={invitation.invitationId}>
                      <span className="invite-avatar">{invitation.inviterDisplayName.slice(0, 1).toUpperCase()}</span>
                      <span><strong>{invitation.inviterDisplayName}</strong><small>Groupe {invitation.region}</small></span>
                      <div>
                        <button onClick={() => void answerPartyInvitation(invitation.invitationId, false)}>Refuser</button>
                        <button className="accept-invite" onClick={() => void answerPartyInvitation(invitation.invitationId, true)}>Rejoindre</button>
                      </div>
                    </article>
                  ))}
                </aside>
              </div>
            ) : (
              <>
                <div className="team-journey" aria-label="Progression du groupe">
                  <span className="complete"><i>1</i><b>Groupe créé</b></span>
                  <em />
                  <span className={party.members.length >= 2 ? 'complete' : ''}><i>2</i><b>Alliés invités</b></span>
                  <em />
                  <span className={party.allReady ? 'complete' : ''}><i>3</i><b>Joueurs prêts</b></span>
                  <em />
                  <span className={queue?.partyId === party.partyId ? 'complete' : ''}><i>4</i><b>Recherche commune</b></span>
                </div>

                <div className="team-content-grid">
                  <section className="team-roster-card">
                    <header>
                      <span>
                        <small>ROSTER · {party.members.length}/{party.capacity}</small>
                        <strong>{party.viewerIsLeader ? 'Tu es le chef du groupe' : 'Groupe de ton allié'}</strong>
                      </span>
                      <div className="team-region-selector">
                        {(['EUW', 'EUNE', 'NA'] as Region[]).map((candidate) => (
                          <button
                            className={party.region === candidate ? 'selected' : ''}
                            disabled={!party.viewerIsLeader || Boolean(queue)}
                            key={candidate}
                            onClick={() => void changePartyRegion(candidate)}
                          >{candidate}</button>
                        ))}
                      </div>
                    </header>

                    <div className="team-member-list">
                      {party.members.map((member) => {
                        const isViewer = member.playerId === session.playerId
                        const canToggleReady = isViewer || (party.viewerIsLeader && member.simulated)
                        return (
                          <article className={member.ready ? 'team-member ready' : 'team-member'} key={member.playerId}>
                            <span className="member-avatar">{member.displayName.slice(0, 1).toUpperCase()}<i className={member.online ? 'online' : ''} /></span>
                            <span className="member-identity">
                              <strong>{member.displayName} {isViewer && <small>TOI</small>}</strong>
                              <small>{member.simulated ? 'JOUEUR SIMULÉ LOCAL' : member.online ? 'EN LIGNE' : 'HORS LIGNE'}</small>
                            </span>
                            <span className="member-roles">
                              <b>{laneLabels[member.primaryRole]}</b>
                              <small>puis {laneLabels[member.secondaryRole]}</small>
                            </span>
                            {canToggleReady ? (
                              <button className={member.ready ? 'ready-toggle active' : 'ready-toggle'} disabled={busy || Boolean(queue)} onClick={() => void setPartyReady(member, !member.ready)}>
                                {member.ready ? 'Prêt ✓' : 'Je suis prêt'}
                              </button>
                            ) : <span className={member.ready ? 'member-state ready' : 'member-state'}>{member.ready ? 'Prêt ✓' : 'En attente'}</span>}
                            {party.viewerIsLeader && !isViewer && (
                              <button className="member-remove" disabled={Boolean(queue)} onClick={() => void removePartyMember(member)} aria-label={`Retirer ${member.displayName}`}>×</button>
                            )}
                          </article>
                        )
                      })}
                      {Array.from({ length: party.capacity - party.members.length }, (_, index) => (
                        <div className="team-open-slot" key={`slot-${index}`}><span>+</span><small>PLACE DISPONIBLE</small></div>
                      ))}
                    </div>
                  </section>

                  <aside className="team-control-card">
                    <p className="micro-label">INVITER UN ALLIÉ</p>
                    {party.viewerIsLeader ? (
                      <>
                        <div className="team-invite-form">
                          <input
                            value={inviteName}
                            maxLength={24}
                            disabled={party.members.length >= party.capacity || Boolean(queue)}
                            onChange={(event) => setInviteName(event.target.value)}
                            onKeyDown={(event) => { if (event.key === 'Enter') void invitePlayer() }}
                            placeholder="Pseudo du joueur"
                          />
                          <button disabled={busy || inviteName.trim().length < 3 || party.members.length >= party.capacity || Boolean(queue)} onClick={() => void invitePlayer()}>Inviter</button>
                        </div>
                        <small className="local-party-note">Test local : un pseudo encore inconnu rejoint comme joueur simulé. Tu peux confirmer son état prêt.</small>
                      </>
                    ) : <p className="team-wait-note">Seul le chef du groupe peut envoyer des invitations.</p>}

                    {party.invitations.length > 0 && (
                      <div className="pending-invites">
                        <small>EN ATTENTE</small>
                        {party.invitations.map((invitation) => <span key={invitation.invitationId}>{invitation.displayName}<i>Invitation envoyée</i></span>)}
                      </div>
                    )}

                    <div className="team-search-summary">
                      <span><small>RÉGION</small><strong>{party.region}</strong></span>
                      <span><small>PRÊTS</small><strong>{party.members.filter((member) => member.ready).length}/{party.members.length}</strong></span>
                    </div>

                    {queue?.partyId === party.partyId ? (
                      <button className="team-search-button searching" disabled={busy || !party.viewerIsLeader} onClick={() => void toggleQueue()}>
                        <span>Recherche en cours</span><small>{queueElapsed} · cliquer pour arrêter</small>
                      </button>
                    ) : (
                      <button className="team-search-button" disabled={busy || !party.viewerIsLeader || !party.allReady || party.members.length < 2} onClick={() => void startPartySearch()}>
                        <span>Lancer la recherche commune</span><small>{party.allReady ? `${party.members.length} joueurs entreront ensemble` : 'Tous les joueurs doivent être prêts'}</small>
                      </button>
                    )}

                    {!party.viewerIsLeader && <p className="team-wait-note">Le chef lancera la recherche lorsque tout le monde sera prêt.</p>}
                    {partyMessage && <p className="profile-feedback" role="status">{partyMessage}</p>}
                    <button className="leave-team-button" disabled={busy || Boolean(queue)} onClick={() => void leaveParty()}>{party.viewerIsLeader ? 'Dissoudre le groupe' : 'Quitter le groupe'}</button>
                    <small className="party-id">GROUPE #{party.partyId.slice(0, 8).toUpperCase()}</small>
                  </aside>
                </div>
              </>
            )}
            {!party && partyMessage && <p className="profile-feedback team-global-feedback" role="status">{partyMessage}</p>}
          </section>
        </div>
      )}

      {duelOpen && session && (
        <div className="duel-screen" role="dialog" aria-modal="true" aria-labelledby="duel-title">
          <section className="duel-shell">
            <header className="duel-heading">
              <div><p className="kicker">DUEL CLASSÉ · GLICKO-2</p><h2 id="duel-title">Duel direct</h2><p>Pas de matchmaking : choisis un joueur, puis chaque PC lance son watcher.</p></div>
              <button className="profile-close" onClick={() => setDuelOpen(false)} aria-label="Fermer les duels">×</button>
            </header>
            <div className="duel-status-bar">
              <span className={watcherOnline ? 'profile-presence online' : 'profile-presence'}><i /> Watcher {watcherOnline ? 'détecté' : 'hors ligne'}</span>
              <span>Riot ID : <strong>{profile?.riotId ?? 'non lié'}</strong></span>
            </div>
            <div className="duel-layout">
              <section className="duel-create-card">
                <p className="micro-label">INVITATION DIRECTE</p>
                <h3>Défier un pseudo Showdown</h3>
                <p>Exemple local : <strong>local-player</strong> défie <strong>local-player2</strong>.</p>
                <label><span>PSEUDO DU SITE</span><input value={duelOpponentName} onChange={event => setDuelOpponentName(event.target.value)} placeholder="local-player2" /></label>
                <button disabled={busy || !profile?.riotId || !duelOpponentName.trim()} onClick={() => void createDuel()}>Envoyer l’invitation</button>
                {!profile?.riotId && <small>Ouvre ton profil et clique « Lier via le watcher » avant de créer un duel.</small>}
              </section>
              <section className="duel-list-card">
                <header><div><p className="micro-label">TES DUELS</p><h3>Invitations et parties</h3></div><button onClick={() => void loadDuels()}>↻</button></header>
                {duelChallenges.length === 0 && <p className="duel-empty">Aucune invitation pour le moment.</p>}
                {duelChallenges.map(challenge => {
                  const opponentPlayerId = challenge.host ? challenge.opponentId : challenge.challengerId
                  const opponentDisplayName = duelPlayerNames[opponentPlayerId] ?? 'Adversaire'
                  return <article className="duel-invitation" key={challenge.challengeId}>
                    <span><strong>{opponentDisplayName}</strong><small>{challenge.region} · {challenge.host ? 'Tu es l’hôte' : 'Tu es invité'} · {challenge.status}</small></span>
                    <div>
                      {challenge.status === 'PENDING' && challenge.incoming && <><button onClick={() => void answerDuel(challenge.challengeId, 'decline')}>Refuser</button><button className="primary" onClick={() => void answerDuel(challenge.challengeId, 'accept')}>Accepter</button></>}
                      {challenge.status === 'PENDING' && challenge.host && <button onClick={() => void answerDuel(challenge.challengeId, 'cancel')}>Annuler</button>}
                      {challenge.status === 'ACCEPTED' && challenge.matchId && <button className="primary" disabled={busy} onClick={() => void startWatcher(challenge)}>Lancer mon watcher</button>}
                      {challenge.status === 'COMPLETED' && <span className="duel-completed">Terminé</span>}
                    </div>
                  </article>
                })}
              </section>
            </div>
            <div className="duel-flow"><span>1 · Invitation directe</span><i>→</i><span>2 · Acceptation</span><i>→</i><span>3 · Deux watchers</span><i>→</i><span>4 · Lobby + résultat vérifié</span></div>
            {duelMessage && <p className="profile-feedback" role="status">{duelMessage}</p>}
          </section>
        </div>
      )}

      {profileOpen && session && (
        <div className="profile-screen" role="dialog" aria-modal="true" aria-labelledby="profile-title">
          <section className="profile-shell">
            <header className="profile-heading">
              <div>
                <p className="kicker">IDENTITÉ JOUEUR</p>
                <h2 id="profile-title">Ton profil</h2>
                <p>Personnalise ce que les autres joueurs verront dans Showdown.</p>
              </div>
              <button className="profile-close" onClick={() => setProfileOpen(false)} aria-label="Fermer le profil">×</button>
            </header>

            <div className="profile-layout">
              <aside className="profile-summary-card">
                <span className="profile-large-avatar">{playerDisplayName.slice(0, 1).toUpperCase()}</span>
                <strong>{playerDisplayName}</strong>
                <span className={profile?.online ? 'profile-presence online' : 'profile-presence'}>
                  <i /> {profile?.online ? 'En ligne' : 'Hors ligne'}
                </span>
                <dl>
                  <div><dt>Région</dt><dd>{profile?.region ?? '—'}</dd></div>
                  <div><dt>Rôle favori</dt><dd>{profile ? laneLabels[profile.primaryRole] : '—'}</dd></div>
                  <div><dt>Compte Riot</dt><dd>{profile?.riotId ?? 'Non lié'}</dd></div>
                  <div><dt>Identifiant</dt><dd>{session.playerId.slice(0, 8)}</dd></div>
                </dl>
                <small>La présence expire automatiquement si cette page est fermée.</small>
              </aside>

              <div className="profile-form-card">
                <label className="profile-field">
                  <span>PSEUDO PUBLIC</span>
                  <input
                    value={profileName}
                    minLength={3}
                    maxLength={24}
                    onChange={(event) => {
                      setProfileName(event.target.value)
                      setProfileMessage(null)
                    }}
                    placeholder="Ton pseudo"
                    autoComplete="nickname"
                  />
                  <small>3 à 24 caractères · lettres, chiffres, espaces, point, tiret et underscore.</small>
                </label>

                <div className="profile-setting">
                  <span>RÉGION FAVORITE</span>
                  <div className="profile-region-options">
                    {(['EUW', 'EUNE', 'NA'] as Region[]).map((candidate) => (
                      <button
                        className={profileRegion === candidate ? 'selected' : ''}
                        key={candidate}
                        onClick={() => {
                          setProfileRegion(candidate)
                          setProfileMessage(null)
                        }}
                      >{candidate}</button>
                    ))}
                  </div>
                </div>

                <div className="profile-setting">
                  <span>COMPTE LEAGUE</span>
                  <div className="riot-link-row">
                    <span><strong>{profile?.riotId ?? 'Aucun Riot ID lié'}</strong><small>Le pseudo est lu localement dans le client League.</small></span>
                    <button disabled={busy} onClick={() => void linkRiotAccount()}>{profile?.riotId ? 'Actualiser' : 'Lier via le watcher'}</button>
                  </div>
                </div>

                <div className="profile-setting">
                  <span>RÔLES FAVORIS</span>
                  <div className="role-matrix profile-role-matrix">
                    <div className="role-matrix-label" />
                    {laneRoles.map((role) => <span className="role-name" key={`profile-label-${role}`}>{laneLabels[role]}</span>)}
                    <span className="role-matrix-label primary-label">Principal</span>
                    {laneRoles.map((role) => (
                      <button
                        className={profilePrimaryRole === role ? 'role-choice selected primary' : 'role-choice'}
                        key={`profile-primary-${role}`}
                        onClick={() => selectProfileRole('primary', role)}
                        aria-label={`${laneLabels[role]} en rôle principal favori`}
                      ><span>{laneMarks[role]}</span></button>
                    ))}
                    <span className="role-matrix-label secondary-label">Secondaire</span>
                    {laneRoles.map((role) => (
                      <button
                        className={profileSecondaryRole === role ? 'role-choice selected secondary' : 'role-choice'}
                        key={`profile-secondary-${role}`}
                        onClick={() => selectProfileRole('secondary', role)}
                        aria-label={`${laneLabels[role]} en rôle secondaire favori`}
                      ><span>{laneMarks[role]}</span></button>
                    ))}
                  </div>
                  <small>Le rôle principal reste prioritaire pendant le matchmaking.</small>
                </div>

                {profileMessage && <p className="profile-feedback" role="status">{profileMessage}</p>}
                <div className="profile-actions">
                  <button className="profile-signout" disabled={busy} onClick={() => void disconnect()}>Se déconnecter</button>
                  <button className="profile-save" disabled={busy || !profile} onClick={() => void saveProfile()}>{busy ? 'Enregistrement…' : 'Enregistrer le profil'}</button>
                </div>
              </div>
            </div>
          </section>
        </div>
      )}

      {match && (
        <div className="match-overlay" role="dialog" aria-modal="true" aria-labelledby="ready-title">
          <section className="ready-card">
            <p className="kicker">{match.region} · {match.mode.replaceAll('_', ' ')}</p>
            <h2 id="ready-title">{match.status === 'CONFIRMED' ? 'Match confirmé !' : 'Match trouvé'}</h2>
            <p className="ready-summary">
              {match.status === 'READY_CHECK'
                ? `${acceptedPlayers}/${match.players.length} joueurs prêts · ${readySeconds}s restantes`
                : `Les ${match.players.length} joueurs sont prêts. Retour à l’accueil…`}
            </p>
            <div className="teams-grid">
              {(['BLUE', 'RED'] as const).map((team) => (
                <div className={`team-column ${team.toLowerCase()}`} key={team}>
                  <h3>Équipe {team === 'BLUE' ? 'bleue' : 'rouge'}</h3>
                  {match.players.filter((player) => player.team === team).map((player) => (
                    <div className="ready-player" key={player.playerId}>
                      <span>{displayPlayer(player)}</span>
                      <span className="ready-player-state"><b>{laneLabels[player.assignedRole]}</b><i className={player.readyState.toLowerCase()}>{player.readyState === 'ACCEPTED' ? 'Prêt' : player.readyState === 'DECLINED' ? 'Refusé' : '…'}</i></span>
                    </div>
                  ))}
                </div>
              ))}
            </div>
            {match.status === 'READY_CHECK' && (
              <div className="ready-actions">
                <button className="decline-button" disabled={busy || currentPlayer?.readyState !== 'PENDING'} onClick={() => answerReady(false)}>Refuser</button>
                <button className="accept-button" disabled={busy || currentPlayer?.readyState !== 'PENDING'} onClick={() => answerReady(true)}>Accepter</button>
              </div>
            )}
          </section>
        </div>
      )}

      {lobby && (
        <div className="lobby-screen" role="dialog" aria-modal="true" aria-labelledby="lobby-title">
          <section className="lobby-shell">
            <div className="lobby-heading">
              <div>
                <p className="kicker">LOBBY WEB · {lobby.region}</p>
                <h2 id="lobby-title">Les joueurs sont prêts</h2>
                <p>Équipes et rôles attribués par le serveur de matchmaking.</p>
              </div>
              <span className="lobby-ready-pill"><i /> MATCH CONFIRMÉ</span>
            </div>

            <div className="lobby-progress">
              <strong>{lobby.players.length} <small>/ {lobby.players.length} confirmés</small></strong>
              <span><i /></span>
              <small>Lobby web prêt</small>
            </div>

            <div className="lobby-grid">
              <div className="lobby-teams">
                {(['BLUE', 'RED'] as const).map((team) => (
                  <section className={`lobby-team ${team.toLowerCase()}`} key={team}>
                    <header>
                      <span><small>ÉQUIPE ATTRIBUÉE</small><strong>Équipe {team === 'BLUE' ? 'bleue' : 'rouge'}</strong></span>
                      <small>{orderedTeam(lobby, team).length} joueur{orderedTeam(lobby, team).length > 1 ? 's' : ''}</small>
                    </header>
                    {orderedTeam(lobby, team).map((player) => (
                      <div className={player.playerId === session?.playerId ? 'lobby-player current' : 'lobby-player'} key={player.playerId}>
                        <span className={`lane-emblem ${player.assignedRole.toLowerCase()}`}>{laneMarks[player.assignedRole]}</span>
                        <span className="lobby-identity">
                          <strong>{displayPlayer(player)}</strong>
                          <small>#{lobby.region}{player.bot ? ' · BOT' : ''}</small>
                        </span>
                        {player.playerId === session?.playerId && <span className="you-badge">TOI</span>}
                        <b className="assigned-lane">{laneLabels[player.assignedRole]}</b>
                        <i className="confirmed-mark">✓</i>
                      </div>
                    ))}
                  </section>
                ))}
              </div>

              <aside className="companion-card">
                <p className="micro-label">CONNEXION MANUELLE</p>
                <h3>Rejoins le lobby</h3>
                <p>Utilise ces identifiants dans une partie personnalisée League. Ils sont réservés aux participants du match.</p>
                <div className="manual-credentials">
                  <div className="credential-row">
                    <span><small>NOM DU LOBBY</small><strong>{lobby.lobbyName ?? 'Indisponible'}</strong></span>
                    <button
                      className="credential-copy"
                      disabled={!lobby.lobbyName}
                      onClick={() => copyCredential('name', lobby.lobbyName)}
                      aria-label="Copier le nom du lobby"
                    >{copiedCredential === 'name' ? '✓' : '⧉'}</button>
                  </div>
                  <div className="credential-row">
                    <span><small>MOT DE PASSE</small><strong>{lobby.lobbyPassword ?? 'Indisponible'}</strong></span>
                    <button
                      className="credential-copy"
                      disabled={!lobby.lobbyPassword}
                      onClick={() => copyCredential('password', lobby.lobbyPassword)}
                      aria-label="Copier le mot de passe du lobby"
                    >{copiedCredential === 'password' ? '✓' : '⧉'}</button>
                  </div>
                </div>
                <div className="companion-state"><span>Application web</span><strong>Prête</strong></div>
                <div className={isBotDuel(lobby) && watcherOnline && !['ERROR', 'REVIEW_REQUIRED'].includes(watcherJob?.state ?? '') ? 'companion-state' : 'companion-state muted'}><span>Watcher local</span><strong>{isBotDuel(lobby) ? watcherStateLabel(watcherJob?.state) : 'Companion requis'}</strong></div>
                <button disabled={busy || !isBotDuel(lobby)} onClick={() => void retryBotWatcher()}>{isBotDuel(lobby) ? 'CRÉER LE LOBBY LEAGUE + BOT' : 'OUVRIR LEAGUE · COMPANION REQUIS'}</button>
                {isBotDuel(lobby) && watcherJob?.detail && <small className="local-result-note">Watcher : {watcherJob.detail}</small>}
                <small className="local-result-note">{isBotDuel(lobby)
                  ? 'Le watcher détecte automatiquement le premier sang, 100 CS ou la première tour et transmet son observation.'
                  : 'Le résultat doit provenir d’une source Watcher autorisée.'}</small>
                <small className="security-note">Mot de passe chiffré côté serveur · aucun identifiant LCU exposé.</small>
              </aside>
            </div>
          </section>
        </div>
      )}

      <footer>
        <span>Pinkward · Showdown V2</span>
        <span>Application web · Infrastructure locale sécurisée</span>
      </footer>
    </div>
  )
}

export default App

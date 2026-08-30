package lol.pinkward.showdown.match;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lol.pinkward.showdown.contracts.DuelRatingUpdatedPayload;
import lol.pinkward.showdown.contracts.EventEnvelope;
import lol.pinkward.showdown.contracts.MatchCancelledPayload;
import lol.pinkward.showdown.contracts.MatchConfirmedPayload;
import lol.pinkward.showdown.contracts.PlayerRatingUpdatedPayload;
import org.springframework.http.HttpStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
class MatchApplicationService {

    static final String MATCH_CANCELLED = "MATCH_CANCELLED";
    static final String MATCH_CANCELLED_KEY = "pinkward.match.cancelled.v1";
    static final String MATCH_CONFIRMED = "MATCH_CONFIRMED";
    static final String MATCH_CONFIRMED_KEY = "pinkward.match.confirmed.v1";
    static final String PLAYER_RATING_UPDATED = "PLAYER_RATING_UPDATED";
    static final String PLAYER_RATING_UPDATED_KEY = "pinkward.match.rating-updated.v2";
    static final String DUEL_RATING_UPDATED = "DUEL_RATING_UPDATED";
    static final String DUEL_RATING_UPDATED_KEY = "pinkward.match.duel-rating-updated.v1";

    private final GameMatchRepository matches;
    private final MatchPlayerRepository players;
    private final MatchOutboxRepository outbox;
    private final PlayerRatingRepository ratings;
    private final RatingChangeRepository ratingChanges;
    private final DuelPlayerRatingRepository duelRatings;
    private final DuelRatingChangeRepository duelRatingChanges;
    private final ObjectMapper objectMapper;
    private final LobbyCredentialService lobbyCredentials;
    private final boolean manualResultsEnabled;
    private final MatchRealtimeHub realtime;
    private final RatingSeasonService seasons;
    private final DuelRatingSeasonService duelSeasons;
    private final Clock clock = Clock.systemUTC();

    @Autowired
    MatchApplicationService(
            GameMatchRepository matches,
            MatchPlayerRepository players,
            MatchOutboxRepository outbox,
            PlayerRatingRepository ratings,
            RatingChangeRepository ratingChanges,
            DuelPlayerRatingRepository duelRatings,
            DuelRatingChangeRepository duelRatingChanges,
            ObjectMapper objectMapper,
            LobbyCredentialService lobbyCredentials,
            RatingSeasonService seasons,
            DuelRatingSeasonService duelSeasons,
            MatchRealtimeHub realtime,
            @Value("${pinkward.match.manual-results-enabled:false}") boolean manualResultsEnabled) {
        this.matches = matches;
        this.players = players;
        this.outbox = outbox;
        this.ratings = ratings;
        this.ratingChanges = ratingChanges;
        this.duelRatings = duelRatings;
        this.duelRatingChanges = duelRatingChanges;
        this.objectMapper = objectMapper;
        this.lobbyCredentials = lobbyCredentials;
        this.realtime = realtime;
        this.seasons = seasons;
        this.duelSeasons = duelSeasons;
        this.manualResultsEnabled = manualResultsEnabled;
    }

    MatchApplicationService(
            GameMatchRepository matches,
            MatchPlayerRepository players,
            MatchOutboxRepository outbox,
            PlayerRatingRepository ratings,
            RatingChangeRepository ratingChanges,
            ObjectMapper objectMapper,
            LobbyCredentialService lobbyCredentials,
            boolean manualResultsEnabled) {
        this(matches, players, outbox, ratings, ratingChanges, null, null,
                objectMapper, lobbyCredentials, null, null, null, manualResultsEnabled);
    }

    MatchApplicationService(
            GameMatchRepository matches,
            MatchPlayerRepository players,
            MatchOutboxRepository outbox,
            PlayerRatingRepository ratings,
            RatingChangeRepository ratingChanges,
            DuelPlayerRatingRepository duelRatings,
            DuelRatingChangeRepository duelRatingChanges,
            ObjectMapper objectMapper,
            LobbyCredentialService lobbyCredentials,
            DuelRatingSeasonService duelSeasons,
            boolean manualResultsEnabled) {
        this(matches, players, outbox, ratings, ratingChanges, duelRatings, duelRatingChanges,
                objectMapper, lobbyCredentials, null, duelSeasons, null, manualResultsEnabled);
    }

    @Transactional(readOnly = true)
    MatchSnapshot current(UUID playerId) {
        GameMatch match = matches.findCurrentForPlayer(playerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No active match"));
        return snapshot(match);
    }

    @Transactional(readOnly = true)
    MatchSnapshot currentLobby(UUID playerId) {
        GameMatch match = matches.findCurrentLobbyForPlayer(playerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No active lobby"));
        return snapshot(match);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    MatchHistoryPage history(
            UUID playerId, int page, int size, String region, String mode, LaneRole role, String outcome) {
        var result = matches.findHistoryForPlayer(
                playerId,
                region,
                mode,
                role == null ? null : role.name(),
                outcome,
                PageRequest.of(page, size, Sort.by(
                        Sort.Order.desc("completed_at"),
                        Sort.Order.desc("id"))));
        List<UUID> matchIds = result.stream().map(GameMatch::id).toList();
        Map<UUID, RatingChange> changes = matchIds.isEmpty() ? Map.of()
                : ratingChanges.findByPlayerIdAndMatchIdInOrderByCreatedAtDesc(playerId, matchIds).stream()
                        .collect(Collectors.toMap(
                                RatingChange::matchId, Function.identity(), (first, ignored) -> first));
        Map<UUID, DuelRatingChange> duelChanges = duelRatingChanges == null || matchIds.isEmpty() ? Map.of()
                : duelRatingChanges.findByPlayerIdAndMatchIdInOrderByCreatedAtDesc(playerId, matchIds).stream()
                        .collect(Collectors.toMap(
                                DuelRatingChange::matchId, Function.identity(), (first, ignored) -> first));
        List<MatchHistoryEntry> content = result.stream()
                .map(match -> historyEntry(
                        match, playerId, changes.get(match.id()), duelChanges.get(match.id())))
                .toList();
        return new MatchHistoryPage(content, result.getNumber(), result.getSize(), result.getTotalElements(),
                result.getTotalPages(), result.hasPrevious(), result.hasNext());
    }

    @Transactional(readOnly = true)
    MatchDetail detail(UUID matchId, UUID playerId) {
        GameMatch match = matches.findCompletedForPlayer(matchId, playerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Completed match not found"));
        MatchPlayer viewer = players.findByMatchIdAndPlayerId(matchId, playerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Completed match not found"));
        RatingChange change = ratingChanges.findByMatchIdAndPlayerId(matchId, playerId).orElse(null);
        DuelRatingChange duelChange = duelRatingChanges == null ? null
                : duelRatingChanges.findByMatchIdAndPlayerId(matchId, playerId).orElse(null);
        List<MatchParticipantDetail> roster = players.findByMatchIdOrderByTeamAscPlayerIdAsc(matchId).stream()
                .map(player -> new MatchParticipantDetail(
                        player.playerId(), player.team().name(), player.assignedRole().name(), player.bot(),
                        player.playerId().equals(playerId)))
                .toList();
        return new MatchDetail(
                historyEntry(match, playerId, change, duelChange),
                roster.stream().filter(player -> player.team().equals(viewer.team().name())).toList(),
                roster.stream().filter(player -> !player.team().equals(viewer.team().name())).toList());
    }

    @Transactional(readOnly = true)
    MatchStatistics statistics(UUID playerId, String region) {
        RatingSeason season = seasons == null ? null : seasons.current(clock.instant());
        String seasonCode = season == null ? "S2026" : season.code();
        PlayerRating rating = ratings.findById(playerId)
                .filter(existing -> existing.seasonCode().equals(seasonCode))
                .orElseGet(() -> PlayerRating.initial(playerId, seasonCode, region, clock.instant()));
        List<MatchHistoryEntry> history = history(playerId, 0, 50, null, "FIVE_V_FIVE", null, null).content().stream()
                .filter(entry -> "FIVE_V_FIVE".equals(entry.mode()))
                .filter(entry -> season == null || (!entry.playedAt().isBefore(season.startsAt())
                        && entry.playedAt().isBefore(season.endsAt())))
                .toList();
        Map<String, Long> gamesByRole = history.stream()
                .collect(Collectors.groupingBy(MatchHistoryEntry::role, LinkedHashMap::new, Collectors.counting()));
        double winRate = rating.games() == 0 ? 0 : Math.round(rating.wins() * 10_000.0 / rating.games()) / 100.0;
        return new MatchStatistics(
                rating.rating(),
                rating.peakRating(),
                rating.skillMean(),
                rating.skillDeviation(),
                seasonCode,
                region,
                Math.max(0, (season == null ? 5 : season.placementGames()) - rating.games()),
                rating.progression(),
                rating.games() < (season == null ? 5 : season.placementGames()) ? "PLACEMENT" : rank(rating.rating()),
                rating.games(),
                rating.wins(),
                rating.losses(),
                winRate,
                gamesByRole,
                history.stream().limit(10).map(MatchHistoryEntry::outcome).toList());
    }

    @Transactional(readOnly = true)
    LeaderboardSnapshot leaderboard(UUID viewerId, String region, int limit) {
        RatingSeason season = seasons.current(clock.instant());
        List<PlayerRating> ranked = ratings
                .findBySeasonCodeAndRegionAndGamesGreaterThanEqualOrderByRatingDescUpdatedAtAscPlayerIdAsc(
                        season.code(), region, season.placementGames());
        var entries = java.util.stream.IntStream.range(0, Math.min(Math.max(1, limit), ranked.size()))
                .mapToObj(index -> leaderboardEntry(index, ranked.get(index))).toList();
        LeaderboardEntry viewer = java.util.stream.IntStream.range(0, ranked.size())
                .filter(index -> ranked.get(index).playerId().equals(viewerId))
                .mapToObj(index -> leaderboardEntry(index, ranked.get(index)))
                .findFirst()
                .orElse(null);
        return new LeaderboardSnapshot(season.code(), region, season.startsAt(), season.endsAt(),
                season.placementGames(), ranked.size(), viewer, entries);
    }

    @Transactional(readOnly = true)
    List<RatingSeasonSnapshot> ratingSeasons() {
        Instant now = clock.instant();
        return seasons.all().stream()
                .map(season -> RatingSeasonSnapshot.from(season, now))
                .toList();
    }

    @Transactional(readOnly = true)
    DuelStatistics duelStatistics(UUID playerId, String region) {
        RatingSeason season = duelSeasons.current(clock.instant());
        DuelPlayerRating rating = duelRatings.findById(playerId)
                .filter(existing -> existing.seasonCode().equals(season.code()))
                .orElseGet(() -> DuelPlayerRating.initial(playerId, season.code(), region, clock.instant()));
        double winRate = rating.games() == 0 ? 0
                : Math.round(rating.wins() * 10_000.0 / rating.games()) / 100.0;
        return new DuelStatistics(
                rating.mmr(), rating.peakRating(), rating.rating(), rating.ratingDeviation(), rating.volatility(),
                "GLICKO_2", season.code(), region,
                Math.max(0, season.placementGames() - rating.games()),
                rating.provisional(season.placementGames()), rating.progression(),
                rating.games(), rating.wins(), rating.losses(), winRate);
    }

    @Transactional(readOnly = true)
    DuelLeaderboardSnapshot duelLeaderboard(UUID viewerId, String region, int limit) {
        RatingSeason season = duelSeasons.current(clock.instant());
        List<DuelPlayerRating> ranked = duelRatings
                .findBySeasonCodeAndRegionAndGamesGreaterThanEqualOrderByRatingDescUpdatedAtAscPlayerIdAsc(
                        season.code(), region, season.placementGames());
        var entries = java.util.stream.IntStream.range(0, Math.min(Math.max(1, limit), ranked.size()))
                .mapToObj(index -> duelLeaderboardEntry(index, ranked.get(index), season)).toList();
        DuelLeaderboardEntry viewer = java.util.stream.IntStream.range(0, ranked.size())
                .filter(index -> ranked.get(index).playerId().equals(viewerId))
                .mapToObj(index -> duelLeaderboardEntry(index, ranked.get(index), season))
                .findFirst()
                .orElse(null);
        return new DuelLeaderboardSnapshot("GLICKO_2", season.code(), region, season.startsAt(), season.endsAt(),
                season.placementGames(), ranked.size(), viewer, entries);
    }

    private static LeaderboardEntry leaderboardEntry(int index, PlayerRating rating) {
        double winRate = rating.games() == 0 ? 0
                : Math.round(rating.wins() * 10_000.0 / rating.games()) / 100.0;
        return new LeaderboardEntry(index + 1, rating.playerId(), rating.rating(), rank(rating.rating()),
                rating.games(), rating.wins(), winRate, rating.progression());
    }

    private static DuelLeaderboardEntry duelLeaderboardEntry(
            int index, DuelPlayerRating rating, RatingSeason season) {
        double winRate = rating.games() == 0 ? 0
                : Math.round(rating.wins() * 10_000.0 / rating.games()) / 100.0;
        return new DuelLeaderboardEntry(index + 1, rating.playerId(), rating.mmr(),
                rating.ratingDeviation(), rating.provisional(season.placementGames()),
                rating.games(), rating.wins(), winRate, rating.progression());
    }

    @Transactional
    MatchSnapshot recordResult(UUID matchId, UUID playerId, TeamSide winner) {
        if (!manualResultsEnabled) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Manual results are disabled");
        }
        return recordResultInternal(matchId, playerId, winner);
    }

    @Transactional
    MatchSnapshot recordVerifiedDuelResult(UUID matchId, UUID reporterId, TeamSide winner) {
        return recordResultInternal(matchId, reporterId, winner);
    }

    @Transactional
    MatchSnapshot recordTrustedResult(UUID matchId, TeamSide winner) {
        return recordResultInternal(matchId, null, winner);
    }

    private MatchSnapshot recordResultInternal(UUID matchId, UUID playerId, TeamSide winner) {
        GameMatch match = matches.findByIdForUpdate(matchId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Match not found"));
        if (playerId != null) {
            players.findByMatchIdAndPlayerId(matchId, playerId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Player is not in this match"));
        }

        if (match.status() == MatchStatus.COMPLETED && match.winningTeam() == winner) {
            return snapshot(match);
        }
        if (match.status() != MatchStatus.CONFIRMED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Lobby is not active");
        }

        Instant now = clock.instant();
        List<MatchPlayer> roster = players.findByMatchIdOrderByTeamAscPlayerIdAsc(matchId);
        if (!match.recordResult(winner, now)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Result could not be recorded");
        }
        applyRatings(match, roster, winner, now);
        announce(roster, "MATCH_RESULT");
        return snapshot(match, roster);
    }

    @Transactional
    MatchSnapshot answer(UUID matchId, UUID playerId, boolean accepted) {
        GameMatch match = matches.findById(matchId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Match not found"));
        if (match.status() != MatchStatus.READY_CHECK || match.readyDeadline().isBefore(clock.instant())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ready check is closed");
        }
        MatchPlayer player = players.findByMatchIdAndPlayerId(matchId, playerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Player is not in this match"));
        player.answer(accepted);
        var roster = players.findByMatchIdOrderByTeamAscPlayerIdAsc(matchId);
        if (!accepted) {
            if (match.cancel()) {
                emitCancellation(
                        match,
                        roster.stream()
                                .filter(candidate -> !candidate.playerId().equals(playerId))
                                .map(MatchPlayer::playerId)
                                .toList(),
                        "PLAYER_DECLINED");
            }
        } else if (roster.stream().allMatch(candidate -> candidate.readyState() == ReadyState.ACCEPTED)) {
            var credentials = lobbyCredentials.create(match.id());
            match.confirm(credentials.name(), credentials.encryptedPassword());
            emitConfirmation(match, roster.stream().map(MatchPlayer::playerId).toList());
        }
        announce(roster, match.status() == MatchStatus.CONFIRMED ? "MATCH_CONFIRMED" : "MATCH_UPDATED");
        return snapshot(match, roster);
    }

    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void expireReadyChecks() {
        for (GameMatch match : matches.findByStatusAndReadyDeadlineBefore(MatchStatus.READY_CHECK, clock.instant())) {
            var roster = players.findByMatchIdOrderByTeamAscPlayerIdAsc(match.id());
            if (match.expire()) {
                emitCancellation(
                        match,
                        roster.stream()
                                .filter(candidate -> candidate.readyState() == ReadyState.ACCEPTED)
                                .map(MatchPlayer::playerId)
                                .toList(),
                        "READY_CHECK_EXPIRED");
                announce(roster, "MATCH_EXPIRED");
            }
        }
    }

    private MatchSnapshot snapshot(GameMatch match) {
        return snapshot(match, players.findByMatchIdOrderByTeamAscPlayerIdAsc(match.id()));
    }

    private void announce(List<MatchPlayer> roster, String type) {
        if (realtime != null) realtime.publishAfterCommit(
                roster.stream().map(MatchPlayer::playerId).toList(), type);
    }

    private MatchSnapshot snapshot(GameMatch match, List<MatchPlayer> roster) {
        String password = match.status() == MatchStatus.CONFIRMED
                ? lobbyCredentials.decrypt(match.lobbyPasswordEncrypted())
                : null;
        return MatchSnapshot.from(match, roster, password);
    }

    private void applyRatings(GameMatch match, List<MatchPlayer> roster, TeamSide winner, Instant now) {
        if ("ONE_V_ONE".equals(match.mode())) {
            applyDuelRatings(match, roster, winner, now);
            return;
        }
        if (!"FIVE_V_FIVE".equals(match.mode())) {
            throw new IllegalStateException("Unsupported rated match mode " + match.mode());
        }
        applyTeamRatings(match, roster, winner, now);
    }

    private void applyTeamRatings(GameMatch match, List<MatchPlayer> roster, TeamSide winner, Instant now) {
        List<MatchPlayer> humans = roster.stream().filter(player -> !player.bot()).toList();
        Map<UUID, PlayerRating> byPlayer = new HashMap<>();
        for (MatchPlayer player : humans) {
            byPlayer.put(player.playerId(), seasons == null
                    ? ratings.findById(player.playerId()).orElseGet(() -> PlayerRating.initial(player.playerId(), now))
                    : seasons.prepare(player.playerId(), match.region(), now));
        }

        List<TrueSkillCalculator.Competitor> competitors = roster.stream()
                .map(player -> new TrueSkillCalculator.Competitor(
                        player.playerId(),
                        player.team(),
                        player.bot() ? TrueSkillCalculator.initialSkill() : byPlayer.get(player.playerId()).skill()))
                .toList();
        Map<UUID, TrueSkillCalculator.Skill> updatedSkills = TrueSkillCalculator.rate(competitors, winner);

        record PlannedChange(
                MatchPlayer player,
                PlayerRating rating,
                boolean won,
                int previous,
                int previousPeak,
                TrueSkillCalculator.Skill previousSkill,
                TrueSkillCalculator.Skill newSkill) {}
        List<PlannedChange> planned = humans.stream().map(player -> {
            PlayerRating current = byPlayer.get(player.playerId());
            return new PlannedChange(
                    player,
                    current,
                    player.team() == winner,
                    current.rating(),
                    current.peakRating(),
                    current.skill(),
                    updatedSkills.get(player.playerId()));
        }).toList();

        for (PlannedChange change : planned) {
            change.rating().apply(change.won(), change.newSkill(), now);
            ratingChanges.save(RatingChange.of(
                    match.id(),
                    change.player().playerId(),
                    change.previous(),
                    change.previousPeak(),
                    change.previousSkill(),
                    change.newSkill(),
                    change.rating().rating(),
                    change.rating().seasonCode(),
                    match.region(),
                    now));
            emitRatingUpdate(match, change.rating(), now);
        }
        ratings.saveAll(planned.stream().map(PlannedChange::rating).toList());
    }

    private void applyDuelRatings(GameMatch match, List<MatchPlayer> roster, TeamSide winner, Instant now) {
        if (roster.size() != 2 || roster.stream().map(MatchPlayer::team).distinct().count() != 2) {
            throw new IllegalStateException("A rated ONE_V_ONE match requires two opponents");
        }
        List<MatchPlayer> humans = roster.stream().filter(player -> !player.bot()).toList();
        List<MatchPlayer> bots = roster.stream().filter(MatchPlayer::bot).toList();
        if (humans.size() == 1 && bots.size() == 1) {
            applyBotDuelRating(match, humans.getFirst(), winner, now);
            return;
        }
        if (!bots.isEmpty()) {
            throw new IllegalStateException("A rated ONE_V_ONE match supports either two humans or one human and one bot");
        }
        MatchPlayer firstPlayer = roster.getFirst();
        MatchPlayer secondPlayer = roster.getLast();
        DuelPlayerRating first = duelSeasons.prepare(firstPlayer.playerId(), match.region(), now);
        DuelPlayerRating second = duelSeasons.prepare(secondPlayer.playerId(), match.region(), now);
        Glicko2Calculator.Rating firstPrevious = first.distribution();
        Glicko2Calculator.Rating secondPrevious = second.distribution();
        int firstPreviousMmr = first.mmr();
        int secondPreviousMmr = second.mmr();
        int firstPreviousPeak = first.peakRating();
        int secondPreviousPeak = second.peakRating();

        Glicko2Calculator.DuelResult result = Glicko2Calculator.rateDuel(
                firstPrevious, secondPrevious, firstPlayer.team() == winner);
        first.apply(firstPlayer.team() == winner, result.first(), now);
        second.apply(secondPlayer.team() == winner, result.second(), now);

        duelRatingChanges.save(DuelRatingChange.of(match.id(), first, firstPreviousMmr, firstPreviousPeak,
                firstPrevious, result.first(), match.region(), now));
        duelRatingChanges.save(DuelRatingChange.of(match.id(), second, secondPreviousMmr, secondPreviousPeak,
                secondPrevious, result.second(), match.region(), now));
        duelRatings.saveAll(List.of(first, second));
        emitDuelRatingUpdate(match, first, now);
        emitDuelRatingUpdate(match, second, now);
    }

    private void applyBotDuelRating(GameMatch match, MatchPlayer human, TeamSide winner, Instant now) {
        DuelPlayerRating rating = duelSeasons.prepare(human.playerId(), match.region(), now);
        Glicko2Calculator.Rating previous = rating.distribution();
        int previousMmr = rating.mmr();
        int previousPeak = rating.peakRating();
        boolean won = human.team() == winner;

        // Test bots are stable virtual opponents: they start every duel at the
        // default Glicko-2 distribution and never receive a persisted rating.
        Glicko2Calculator.DuelResult result = Glicko2Calculator.rateDuel(
                previous,
                new Glicko2Calculator.Rating(
                        Glicko2Calculator.INITIAL_RATING,
                        Glicko2Calculator.INITIAL_DEVIATION,
                        Glicko2Calculator.INITIAL_VOLATILITY),
                won);
        rating.apply(won, result.first(), now);

        duelRatingChanges.save(DuelRatingChange.of(match.id(), rating, previousMmr, previousPeak,
                previous, result.first(), match.region(), now));
        duelRatings.save(rating);
        emitDuelRatingUpdate(match, rating, now);
    }

    private MatchHistoryEntry historyEntry(
            GameMatch match, UUID playerId, RatingChange change, DuelRatingChange duelChange) {
        MatchPlayer player = players.findByMatchIdAndPlayerId(match.id(), playerId)
                .orElseThrow(() -> new IllegalStateException("History references a missing match player"));
        boolean duel = "ONE_V_ONE".equals(match.mode());
        int previous = duel
                ? (duelChange == null ? DuelPlayerRating.INITIAL_MMR : duelChange.previousMmr())
                : (change == null ? PlayerRating.INITIAL_RATING : change.previousRating());
        int delta = duel
                ? (duelChange == null ? 0 : duelChange.ratingDelta())
                : (change == null ? 0 : change.ratingDelta());
        int current = duel
                ? (duelChange == null ? previous : duelChange.newMmr())
                : (change == null ? previous : change.newRating());
        return new MatchHistoryEntry(
                match.id(),
                match.region(),
                match.mode(),
                player.team() == match.winningTeam() ? "VICTORY" : "DEFEAT",
                player.team().name(),
                player.assignedRole().name(),
                match.completedAt(),
                previous,
                delta,
                current);
    }

    private static String rank(int rating) {
        if (rating < 900) return "FER";
        if (rating < 1050) return "BRONZE";
        if (rating < 1200) return "ARGENT";
        if (rating < 1350) return "OR";
        if (rating < 1500) return "PLATINE";
        if (rating < 1650) return "ÉMERAUDE";
        if (rating < 1800) return "DIAMANT";
        return "MAÎTRE";
    }

    private void emitRatingUpdate(GameMatch match, PlayerRating rating, Instant now) {
        UUID eventId = UUID.randomUUID();
        var envelope = new EventEnvelope<>(
                eventId,
                PLAYER_RATING_UPDATED,
                2,
                now,
                "match-service",
                match.id(),
                match.id(),
                new PlayerRatingUpdatedPayload(
                        rating.playerId(),
                        rating.rating(),
                        rating.peakRating(),
                        rating.games(),
                        rating.skillMean(),
                        rating.skillDeviation()));
        outbox.save(MatchOutboxEvent.pending(
                eventId,
                PLAYER_RATING_UPDATED,
                PLAYER_RATING_UPDATED_KEY,
                serialize(envelope),
                now));
    }

    private void emitDuelRatingUpdate(GameMatch match, DuelPlayerRating rating, Instant now) {
        UUID eventId = UUID.randomUUID();
        var envelope = new EventEnvelope<>(
                eventId,
                DUEL_RATING_UPDATED,
                1,
                now,
                "match-service",
                match.id(),
                match.id(),
                new DuelRatingUpdatedPayload(
                        rating.playerId(),
                        rating.mmr(),
                        rating.peakRating(),
                        rating.games(),
                        rating.seasonCode(),
                        rating.region(),
                        rating.rating(),
                        rating.ratingDeviation(),
                        rating.volatility()));
        outbox.save(MatchOutboxEvent.pending(
                eventId,
                DUEL_RATING_UPDATED,
                DUEL_RATING_UPDATED_KEY,
                serialize(envelope),
                now));
    }

    private void emitCancellation(GameMatch match, List<UUID> requeuePlayerIds, String reason) {
        UUID eventId = UUID.randomUUID();
        var payload = new MatchCancelledPayload(
                match.id(),
                match.reservationId(),
                reason,
                requeuePlayerIds);
        var envelope = new EventEnvelope<>(
                eventId,
                MATCH_CANCELLED,
                1,
                clock.instant(),
                "match-service",
                match.id(),
                null,
                payload);
        outbox.save(MatchOutboxEvent.pending(
                eventId,
                MATCH_CANCELLED,
                MATCH_CANCELLED_KEY,
                serialize(envelope),
                clock.instant()));
    }

    private void emitConfirmation(GameMatch match, List<UUID> playerIds) {
        UUID eventId = UUID.randomUUID();
        var envelope = new EventEnvelope<>(
                eventId,
                MATCH_CONFIRMED,
                1,
                clock.instant(),
                "match-service",
                match.id(),
                null,
                new MatchConfirmedPayload(match.id(), match.reservationId(), playerIds));
        outbox.save(MatchOutboxEvent.pending(
                eventId,
                MATCH_CONFIRMED,
                MATCH_CONFIRMED_KEY,
                serialize(envelope),
                clock.instant()));
    }

    private String serialize(Object envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Could not serialize MATCH_CANCELLED", exception);
        }
    }
}

package lol.pinkward.showdown.match;

import java.time.Clock;
import java.time.Duration;
import lol.pinkward.showdown.contracts.MatchFoundPayload;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
class MatchFoundConsumer {

    private final GameMatchRepository matches;
    private final MatchPlayerRepository players;
    private final InboxEventRepository inbox;
    private final ObjectMapper objectMapper;
    private final Duration readyCheckTimeout;
    private final RoleAssignmentService roleAssignments;
    private final LobbyCredentialService lobbyCredentials;
    private final MatchRealtimeHub realtime;
    private final Clock clock = Clock.systemUTC();

    @Autowired
    MatchFoundConsumer(
            GameMatchRepository matches,
            MatchPlayerRepository players,
            InboxEventRepository inbox,
            ObjectMapper objectMapper,
            RoleAssignmentService roleAssignments,
            LobbyCredentialService lobbyCredentials,
            MatchRealtimeHub realtime,
            @Value("${pinkward.ready-check.timeout}") Duration readyCheckTimeout) {
        this.matches = matches;
        this.players = players;
        this.inbox = inbox;
        this.objectMapper = objectMapper;
        this.roleAssignments = roleAssignments;
        this.lobbyCredentials = lobbyCredentials;
        this.realtime = realtime;
        this.readyCheckTimeout = readyCheckTimeout;
    }

    MatchFoundConsumer(
            GameMatchRepository matches,
            MatchPlayerRepository players,
            InboxEventRepository inbox,
            ObjectMapper objectMapper,
            RoleAssignmentService roleAssignments,
            LobbyCredentialService lobbyCredentials,
            Duration readyCheckTimeout) {
        this(matches, players, inbox, objectMapper, roleAssignments, lobbyCredentials, null, readyCheckTimeout);
    }

    @RabbitListener(queues = RabbitConfiguration.MATCH_FOUND_QUEUE)
    @Transactional
    public void consume(String message) {
        MatchFoundEnvelope event = deserialize(message);
        if (!"MATCH_FOUND".equals(event.eventType()) || event.eventVersion() != 1
                || !"matchmaking-service".equals(event.producer())) {
            throw new IllegalArgumentException("Unsupported matchmaking event");
        }
        if (inbox.existsById(event.eventId())) return;

        MatchFoundPayload payload = event.payload();
        if (!matches.existsByReservationId(payload.reservationId())) {
            var now = clock.instant();
            GameMatch match = matches.save(GameMatch.readyCheck(
                    payload.reservationId(),
                    payload.region(),
                    payload.mode(),
                    now,
                    now.plus(readyCheckTimeout)));
            var preferences = payload.rolePreferences().stream().collect(
                    java.util.stream.Collectors.toMap(
                            lol.pinkward.showdown.contracts.PlayerRolePreference::playerId,
                            preference -> preference));
            boolean duel = "ONE_V_ONE".equals(payload.mode());
            int teamSize = duel ? 1 : 5;
            var blueRoles = duel
                    ? java.util.Map.of(payload.playerIds().getFirst(), LaneRole.MID)
                    : roleAssignments.assign(payload.playerIds().subList(0, teamSize), preferences);
            var redRoles = duel
                    ? java.util.Map.of(payload.playerIds().getLast(), LaneRole.MID)
                    : roleAssignments.assign(payload.playerIds().subList(teamSize, teamSize * 2), preferences);
            for (int index = 0; index < payload.playerIds().size(); index++) {
                TeamSide team = index < teamSize ? TeamSide.BLUE : TeamSide.RED;
                var playerId = payload.playerIds().get(index);
                players.save(MatchPlayer.readyCheck(
                        match.id(),
                        playerId,
                        team,
                        payload.botPlayerIds().contains(playerId),
                        (team == TeamSide.BLUE ? blueRoles : redRoles).get(playerId)));
            }
            if (!duel && payload.botPlayerIds().size() == 10) {
                var credentials = lobbyCredentials.create(match.id());
                match.confirm(credentials.name(), credentials.encryptedPassword());
            }
        }
        inbox.save(InboxEvent.received(event.eventId(), clock.instant()));
        if (realtime != null) realtime.publishAfterCommit(payload.playerIds(), "MATCH_FOUND");
    }

    private MatchFoundEnvelope deserialize(String message) {
        try {
            return objectMapper.readValue(message, MatchFoundEnvelope.class);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("Invalid MATCH_FOUND event", exception);
        }
    }
}

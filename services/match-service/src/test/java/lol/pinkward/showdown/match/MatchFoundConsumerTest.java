package lol.pinkward.showdown.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.IntStream;
import lol.pinkward.showdown.contracts.MatchFoundPayload;
import lol.pinkward.showdown.contracts.PlayerRolePreference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class MatchFoundConsumerTest {

    @Test
    void rejectsAnEventClaimingAnUnexpectedProducer() throws Exception {
        GameMatchRepository matches = mock(GameMatchRepository.class);
        MatchPlayerRepository players = mock(MatchPlayerRepository.class);
        InboxEventRepository inbox = mock(InboxEventRepository.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        UUID reservationId = UUID.randomUUID();
        var playerIds = IntStream.range(0, 10).mapToObj(index -> UUID.randomUUID()).toList();
        var envelope = new MatchFoundEnvelope(
                UUID.randomUUID(), "MATCH_FOUND", 1, Instant.now(), "match-service",
                reservationId, null,
                new MatchFoundPayload(
                        reservationId, "EUW", "FIVE_V_FIVE", playerIds,
                        java.util.List.of(), preferences(playerIds)));
        when(objectMapper.readValue("event", MatchFoundEnvelope.class)).thenReturn(envelope);
        MatchFoundConsumer consumer = new MatchFoundConsumer(
                matches, players, inbox, objectMapper, new RoleAssignmentService(),
                lobbyCredentials(), Duration.ofSeconds(45));

        assertThatThrownBy(() -> consumer.consume("event"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported matchmaking event");
        verify(matches, never()).save(any());
    }

    @Test
    void createsOneMatchTenPlayersAndOneInboxEntry() throws Exception {
        GameMatchRepository matches = mock(GameMatchRepository.class);
        MatchPlayerRepository players = mock(MatchPlayerRepository.class);
        InboxEventRepository inbox = mock(InboxEventRepository.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        UUID eventId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        var playerIds = IntStream.range(0, 10).mapToObj(index -> UUID.randomUUID()).toList();
        MatchFoundEnvelope envelope = new MatchFoundEnvelope(
                eventId,
                "MATCH_FOUND",
                1,
                Instant.now(),
                "matchmaking-service",
                reservationId,
                null,
                new MatchFoundPayload(
                        reservationId, "EUW", "FIVE_V_FIVE", playerIds,
                        java.util.List.of(), preferences(playerIds)));
        when(objectMapper.readValue("event", MatchFoundEnvelope.class)).thenReturn(envelope);
        when(matches.save(any(GameMatch.class))).thenAnswer(invocation -> invocation.getArgument(0));
        MatchFoundConsumer consumer = new MatchFoundConsumer(
                matches, players, inbox, objectMapper, new RoleAssignmentService(),
                lobbyCredentials(), Duration.ofSeconds(45));

        consumer.consume("event");

        verify(matches).save(any(GameMatch.class));
        verify(players, times(10)).save(any(MatchPlayer.class));
        verify(inbox).save(any(InboxEvent.class));
    }

    @Test
    void ignoresAnEventAlreadyRecordedInTheInbox() throws Exception {
        GameMatchRepository matches = mock(GameMatchRepository.class);
        MatchPlayerRepository players = mock(MatchPlayerRepository.class);
        InboxEventRepository inbox = mock(InboxEventRepository.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        UUID eventId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        var playerIds = IntStream.range(0, 10).mapToObj(index -> UUID.randomUUID()).toList();
        MatchFoundEnvelope envelope = new MatchFoundEnvelope(
                eventId,
                "MATCH_FOUND",
                1,
                Instant.now(),
                "matchmaking-service",
                reservationId,
                null,
                new MatchFoundPayload(
                        reservationId, "EUW", "FIVE_V_FIVE", playerIds,
                        java.util.List.of(), preferences(playerIds)));
        when(objectMapper.readValue("event", MatchFoundEnvelope.class)).thenReturn(envelope);
        when(inbox.existsById(eventId)).thenReturn(true);
        MatchFoundConsumer consumer = new MatchFoundConsumer(
                matches, players, inbox, objectMapper, new RoleAssignmentService(),
                lobbyCredentials(), Duration.ofSeconds(45));

        consumer.consume("event");

        verify(matches, never()).save(any());
        verify(players, never()).save(any());
        assertThat(eventId).isNotNull();
    }

    @Test
    void confirmsAnAllBotMatchImmediately() throws Exception {
        GameMatchRepository matches = mock(GameMatchRepository.class);
        MatchPlayerRepository players = mock(MatchPlayerRepository.class);
        InboxEventRepository inbox = mock(InboxEventRepository.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        UUID reservationId = UUID.randomUUID();
        var botIds = IntStream.range(0, 10).mapToObj(index -> UUID.randomUUID()).toList();
        var envelope = new MatchFoundEnvelope(
                UUID.randomUUID(), "MATCH_FOUND", 1, Instant.now(), "matchmaking-service",
                reservationId, null,
                new MatchFoundPayload(
                        reservationId, "EUW", "FIVE_V_FIVE", botIds, botIds, preferences(botIds)));
        when(objectMapper.readValue("event", MatchFoundEnvelope.class)).thenReturn(envelope);
        when(matches.save(any(GameMatch.class))).thenAnswer(invocation -> invocation.getArgument(0));
        var match = org.mockito.ArgumentCaptor.forClass(GameMatch.class);
        var savedPlayers = org.mockito.ArgumentCaptor.forClass(MatchPlayer.class);
        MatchFoundConsumer consumer = new MatchFoundConsumer(
                matches, players, inbox, objectMapper, new RoleAssignmentService(),
                lobbyCredentials(), Duration.ofSeconds(45));

        consumer.consume("event");

        verify(matches).save(match.capture());
        verify(players, times(10)).save(savedPlayers.capture());
        assertThat(match.getValue().status()).isEqualTo(MatchStatus.CONFIRMED);
        assertThat(match.getValue().lobbyName()).startsWith("SWD-");
        assertThat(match.getValue().lobbyPasswordEncrypted()).isNotBlank();
        assertThat(savedPlayers.getAllValues())
                .allMatch(MatchPlayer::bot)
                .allMatch(player -> player.readyState() == ReadyState.ACCEPTED);
    }

    @Test
    void createsAOneVersusOneMatchWithOneHumanOnEachTeam() throws Exception {
        GameMatchRepository matches = mock(GameMatchRepository.class);
        MatchPlayerRepository players = mock(MatchPlayerRepository.class);
        InboxEventRepository inbox = mock(InboxEventRepository.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        UUID reservationId = UUID.randomUUID();
        var playerIds = java.util.List.of(UUID.randomUUID(), UUID.randomUUID());
        var envelope = new MatchFoundEnvelope(
                UUID.randomUUID(), "MATCH_FOUND", 1, Instant.now(), "matchmaking-service",
                reservationId, null,
                new MatchFoundPayload(
                        reservationId, "EUW", "ONE_V_ONE", playerIds, java.util.List.of(), preferences(playerIds)));
        when(objectMapper.readValue("event", MatchFoundEnvelope.class)).thenReturn(envelope);
        when(matches.save(any(GameMatch.class))).thenAnswer(invocation -> invocation.getArgument(0));
        var savedMatch = org.mockito.ArgumentCaptor.forClass(GameMatch.class);
        var savedPlayers = org.mockito.ArgumentCaptor.forClass(MatchPlayer.class);
        MatchFoundConsumer consumer = new MatchFoundConsumer(
                matches, players, inbox, objectMapper, new RoleAssignmentService(),
                lobbyCredentials(), Duration.ofSeconds(45));

        consumer.consume("event");

        verify(matches).save(savedMatch.capture());
        verify(players, times(2)).save(savedPlayers.capture());
        assertThat(savedMatch.getValue().mode()).isEqualTo("ONE_V_ONE");
        assertThat(savedMatch.getValue().status()).isEqualTo(MatchStatus.READY_CHECK);
        assertThat(savedPlayers.getAllValues()).extracting(MatchPlayer::team)
                .containsExactly(TeamSide.BLUE, TeamSide.RED);
        assertThat(savedPlayers.getAllValues())
                .allMatch(player -> !player.bot())
                .allMatch(player -> player.assignedRole() == LaneRole.MID);
    }

    private static java.util.List<PlayerRolePreference> preferences(java.util.List<UUID> playerIds) {
        var roles = java.util.List.of("TOP", "JUNGLE", "MID", "BOT", "SUPPORT");
        return IntStream.range(0, playerIds.size())
                .mapToObj(index -> new PlayerRolePreference(
                        playerIds.get(index),
                        roles.get(index % roles.size()),
                        roles.get((index + 1) % roles.size())))
                .toList();
    }

    private static LobbyCredentialService lobbyCredentials() {
        return new LobbyCredentialService("test-lobby-credential-key-change-me");
    }
}

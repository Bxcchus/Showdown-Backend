package lol.pinkward.showdown.match;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "matches")
class GameMatch {

    @Id
    private UUID id;

    @Column(name = "reservation_id", nullable = false, unique = true)
    private UUID reservationId;

    @Column(nullable = false, length = 16)
    private String region;

    @Column(nullable = false, length = 24)
    private String mode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private MatchStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "ready_deadline", nullable = false)
    private Instant readyDeadline;

    @Column(name = "lobby_name", length = 32)
    private String lobbyName;

    @Column(name = "lobby_password_encrypted", length = 160)
    private String lobbyPasswordEncrypted;

    @Enumerated(EnumType.STRING)
    @Column(name = "winning_team", length = 8)
    private TeamSide winningTeam;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected GameMatch() {}

    static GameMatch readyCheck(UUID reservationId, String region, String mode, Instant now, Instant deadline) {
        GameMatch match = new GameMatch();
        match.id = UUID.randomUUID();
        match.reservationId = reservationId;
        match.region = region;
        match.mode = mode;
        match.status = MatchStatus.READY_CHECK;
        match.createdAt = now;
        match.readyDeadline = deadline;
        return match;
    }

    void confirm(String name, String encryptedPassword) {
        if (status == MatchStatus.READY_CHECK) {
            lobbyName = name;
            lobbyPasswordEncrypted = encryptedPassword;
            status = MatchStatus.CONFIRMED;
        }
    }

    boolean cancel() {
        if (status != MatchStatus.READY_CHECK) return false;
        status = MatchStatus.CANCELLED;
        return true;
    }

    boolean cancelConfirmed() {
        if (status != MatchStatus.CONFIRMED) return false;
        status = MatchStatus.CANCELLED;
        return true;
    }

    boolean expire() {
        if (status != MatchStatus.READY_CHECK) return false;
        status = MatchStatus.EXPIRED;
        return true;
    }

    boolean recordResult(TeamSide winner, Instant now) {
        if (status != MatchStatus.CONFIRMED) return false;
        winningTeam = winner;
        completedAt = now;
        status = MatchStatus.COMPLETED;
        return true;
    }

    UUID id() { return id; }
    UUID reservationId() { return reservationId; }
    String region() { return region; }
    String mode() { return mode; }
    MatchStatus status() { return status; }
    Instant createdAt() { return createdAt; }
    Instant readyDeadline() { return readyDeadline; }
    String lobbyName() { return lobbyName; }
    String lobbyPasswordEncrypted() { return lobbyPasswordEncrypted; }
    TeamSide winningTeam() { return winningTeam; }
    Instant completedAt() { return completedAt; }
}

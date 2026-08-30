package lol.pinkward.showdown.player;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "parties")
class Party {

    @Id
    private UUID id;

    @Column(name = "leader_id", nullable = false)
    private UUID leaderId;

    @Column(nullable = false, length = 16)
    private String region;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected Party() {}

    static Party create(UUID leaderId, String region, Instant now) {
        Party party = new Party();
        party.id = UUID.randomUUID();
        party.leaderId = leaderId;
        party.region = region;
        party.createdAt = now;
        party.updatedAt = now;
        return party;
    }

    void updateRegion(String region, Instant now) {
        this.region = region;
        this.updatedAt = now;
    }

    UUID id() { return id; }
    UUID leaderId() { return leaderId; }
    String region() { return region; }
    Instant createdAt() { return createdAt; }
}

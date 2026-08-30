package lol.pinkward.showdown.player;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
class PartyService {

    private static final List<String> ROLES = List.of("TOP", "JUNGLE", "MID", "BOT", "SUPPORT");
    private static final Set<String> REGIONS = Set.of("EUW", "EUNE", "NA");

    private final PartyRepository parties;
    private final PartyMemberRepository members;
    private final PartyInvitationRepository invitations;
    private final PlayerProfileRepository profiles;
    private final PlayerProfileService profileService;
    private final PartyQueueClient queue;
    private final PartyRealtimeHub realtime;
    private final boolean localSimulation;
    private final Duration invitationTtl;
    private final Clock clock;

    @Autowired
    PartyService(
            PartyRepository parties,
            PartyMemberRepository members,
            PartyInvitationRepository invitations,
            PlayerProfileRepository profiles,
            PlayerProfileService profileService,
            PartyQueueClient queue,
            PartyRealtimeHub realtime,
            @Value("${pinkward.party.local-simulation:false}") boolean localSimulation,
            @Value("${pinkward.party.invitation-ttl:5m}") Duration invitationTtl) {
        this(parties, members, invitations, profiles, profileService, queue, realtime,
                localSimulation, invitationTtl, Clock.systemUTC());
    }

    PartyService(
            PartyRepository parties,
            PartyMemberRepository members,
            PartyInvitationRepository invitations,
            PlayerProfileRepository profiles,
            PlayerProfileService profileService,
            PartyQueueClient queue,
            PartyRealtimeHub realtime,
            boolean localSimulation,
            Duration invitationTtl,
            Clock clock) {
        this.parties = parties;
        this.members = members;
        this.invitations = invitations;
        this.profiles = profiles;
        this.profileService = profileService;
        this.queue = queue;
        this.realtime = realtime;
        this.localSimulation = localSimulation;
        this.invitationTtl = invitationTtl;
        this.clock = clock;
    }

    @Transactional
    PartySnapshot create(UUID playerId, String identityUsername) {
        if (members.findByPlayerId(playerId).isPresent()) {
            throw conflict("Tu appartiens déjà à un groupe");
        }
        Instant now = clock.instant();
        profileService.me(playerId, identityUsername);
        PlayerProfile profile = profiles.findById(playerId).orElseThrow();
        Party party = parties.save(Party.create(playerId, profile.region(), now));
        members.save(PartyMember.real(party.id(), profile, now));
        return announce(snapshot(party, playerId, now), "PARTY_UPDATED");
    }

    @Transactional(readOnly = true)
    PartySnapshot current(UUID playerId) {
        Instant now = clock.instant();
        Party party = currentParty(playerId);
        return snapshot(party, playerId, now);
    }

    @Transactional
    PartySnapshot updateRegion(UUID playerId, String region) {
        Party party = currentParty(playerId);
        requireLeader(party, playerId);
        party.updateRegion(normalizeRegion(region), clock.instant());
        members.findByPartyIdOrderByJoinedAtAscIdAsc(party.id()).forEach(member -> member.setReady(false));
        return announce(snapshot(party, playerId, clock.instant()), "PARTY_UPDATED");
    }

    @Transactional
    PartySnapshot invite(UUID playerId, String displayName) {
        Instant now = clock.instant();
        Party party = currentParty(playerId);
        requireLeader(party, playerId);
        List<PartyMember> roster = members.findByPartyIdOrderByJoinedAtAscIdAsc(party.id());
        if (roster.size() >= 5) throw conflict("Le groupe est complet");

        String normalizedName = normalizeDisplayName(displayName);
        var target = profiles.findByDisplayNameIgnoreCase(normalizedName);
        if (target.isPresent()) {
            PlayerProfile profile = target.get();
            if (members.findByPlayerId(profile.playerId()).isPresent()) {
                throw conflict("Ce joueur appartient déjà à un groupe");
            }
            boolean alreadyPending = invitations.findByPartyIdOrderByCreatedAtDesc(party.id()).stream()
                    .anyMatch(invitation -> invitation.inviteeId().equals(profile.playerId())
                            && invitation.pendingAt(now));
            if (alreadyPending) throw conflict("Une invitation est déjà en attente");
            invitations.save(PartyInvitation.pending(
                    party.id(), playerId, profile.playerId(), profile.displayName(), now, now.plus(invitationTtl)));
        } else {
            if (!localSimulation) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Joueur introuvable");
            }
            UUID simulatedId = UUID.nameUUIDFromBytes(
                    ("showdown-local-party:" + party.id() + ":" + normalizedName.toLowerCase(Locale.ROOT))
                            .getBytes(StandardCharsets.UTF_8));
            if (members.findByPlayerId(simulatedId).isPresent()) {
                throw conflict("Ce joueur simulé est déjà dans le groupe");
            }
            String primary = firstAvailableRole(roster);
            String secondary = ROLES.get((ROLES.indexOf(primary) + 1) % ROLES.size());
            PartyInvitation invitation = invitations.save(PartyInvitation.pending(
                    party.id(), playerId, simulatedId, normalizedName, now, now.plus(invitationTtl)));
            invitation.accept(now);
            members.save(PartyMember.simulated(
                    party.id(), simulatedId, normalizedName, primary, secondary, now));
        }
        return announce(snapshot(party, playerId, now), "INVITATION_UPDATED");
    }

    @Transactional(readOnly = true)
    List<PartyInvitationView> invitations(UUID playerId) {
        Instant now = clock.instant();
        return invitations.findByInviteeIdAndStatusOrderByCreatedAtDesc(playerId, PartyInvitation.Status.PENDING)
                .stream()
                .filter(invitation -> invitation.pendingAt(now))
                .map(invitation -> {
                    Party party = parties.findById(invitation.partyId()).orElseThrow();
                    String inviter = profiles.findById(invitation.inviterId())
                            .map(PlayerProfile::displayName)
                            .orElse("Joueur");
                    return new PartyInvitationView(
                            invitation.id(), party.id(), inviter, party.region(), invitation.expiresAt());
                })
                .toList();
    }

    @Transactional
    PartySnapshot accept(UUID playerId, UUID invitationId) {
        Instant now = clock.instant();
        PartyInvitation invitation = invitationForPlayer(playerId, invitationId);
        if (!invitation.pendingAt(now)) {
            if (invitation.status() == PartyInvitation.Status.PENDING) invitation.expire(now);
            throw new ResponseStatusException(HttpStatus.GONE, "Cette invitation a expiré");
        }
        if (members.findByPlayerId(playerId).isPresent()) throw conflict("Tu appartiens déjà à un groupe");
        Party party = parties.findById(invitation.partyId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Groupe introuvable"));
        if (members.countByPartyId(party.id()) >= 5) throw conflict("Le groupe est complet");
        PlayerProfile profile = profiles.findById(playerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Profil introuvable"));
        members.save(PartyMember.real(party.id(), profile, now));
        invitation.accept(now);
        return announce(snapshot(party, playerId, now), "PARTY_UPDATED");
    }

    @Transactional
    void decline(UUID playerId, UUID invitationId) {
        PartyInvitation invitation = invitationForPlayer(playerId, invitationId);
        if (invitation.status() == PartyInvitation.Status.PENDING) {
            invitation.decline(clock.instant());
            var recipients = new java.util.HashSet<UUID>();
            recipients.add(playerId);
            members.findByPartyIdOrderByJoinedAtAscIdAsc(invitation.partyId())
                    .forEach(member -> recipients.add(member.playerId()));
            realtime.publishAfterCommit(Set.copyOf(recipients), "INVITATION_UPDATED");
        }
    }

    @Transactional
    PartySnapshot ready(UUID playerId, boolean ready) {
        PartyMember member = members.findByPlayerId(playerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Aucun groupe actif"));
        member.setReady(ready);
        Party party = parties.findById(member.partyId()).orElseThrow();
        return announce(snapshot(party, playerId, clock.instant()), "PARTY_READY_UPDATED");
    }

    @Transactional
    PartySnapshot setSimulatedReady(UUID playerId, UUID memberId, boolean ready) {
        Party party = currentParty(playerId);
        requireLeader(party, playerId);
        PartyMember member = members.findByPlayerId(memberId)
                .filter(candidate -> candidate.partyId().equals(party.id()) && candidate.simulated())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Joueur simulé introuvable"));
        if (!localSimulation) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Simulation locale désactivée");
        member.setReady(ready);
        return announce(snapshot(party, playerId, clock.instant()), "PARTY_READY_UPDATED");
    }

    @Transactional
    PartySnapshot removeMember(UUID playerId, UUID memberId) {
        Party party = currentParty(playerId);
        requireLeader(party, playerId);
        if (memberId.equals(playerId)) throw conflict("Utilise Quitter le groupe");
        PartyMember member = members.findByPlayerId(memberId)
                .filter(candidate -> candidate.partyId().equals(party.id()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Membre introuvable"));
        members.delete(member);
        return announce(snapshot(party, playerId, clock.instant()), "PARTY_UPDATED");
    }

    @Transactional
    void leave(UUID playerId) {
        Party party = currentParty(playerId);
        Set<UUID> recipients = members.findByPartyIdOrderByJoinedAtAscIdAsc(party.id()).stream()
                .map(PartyMember::playerId).collect(java.util.stream.Collectors.toSet());
        if (party.leaderId().equals(playerId)) {
            try { queue.leave(party.id()); } catch (RuntimeException ignored) { }
            parties.delete(party);
        } else {
            members.deleteByPartyIdAndPlayerId(party.id(), playerId);
        }
        realtime.publishAfterCommit(recipients, "PARTY_UPDATED");
    }

    @Transactional(readOnly = true)
    PartySnapshot startSearch(UUID playerId, String idempotencyKey) {
        Party party = currentParty(playerId);
        requireLeader(party, playerId);
        List<PartyMember> roster = members.findByPartyIdOrderByJoinedAtAscIdAsc(party.id());
        if (roster.size() < 2) throw conflict("Invite au moins un joueur avant de chercher");
        if (roster.stream().anyMatch(member -> !member.ready())) {
            throw conflict("Tous les joueurs doivent être prêts");
        }
        var queueMembers = roster.stream().map(member -> {
            PlayerProfile profile = member.simulated() ? null : profiles.findById(member.playerId()).orElse(null);
            return new PartyQueueClient.Member(
                    member.playerId(),
                    profile == null ? member.primaryRole() : profile.primaryRole(),
                    profile == null ? member.secondaryRole() : profile.secondaryRole(),
                    member.simulated());
        }).toList();
        try {
            queue.join(party.id(), party.region(), queueMembers, idempotencyKey);
        } catch (PartyQueueClient.PartyQueueException exception) {
            HttpStatus status = exception.status() == 409 ? HttpStatus.CONFLICT : HttpStatus.BAD_GATEWAY;
            throw new ResponseStatusException(status, exception.getMessage(), exception);
        }
        return announce(snapshot(party, playerId, clock.instant()), "PARTY_SEARCH_UPDATED");
    }

    @Transactional(readOnly = true)
    void stopSearch(UUID playerId) {
        Party party = currentParty(playerId);
        requireLeader(party, playerId);
        try {
            queue.leave(party.id());
        } catch (PartyQueueClient.PartyQueueException exception) {
            HttpStatus status = exception.status() == 409 ? HttpStatus.CONFLICT : HttpStatus.BAD_GATEWAY;
            throw new ResponseStatusException(status, exception.getMessage(), exception);
        }
        announce(snapshot(party, playerId, clock.instant()), "PARTY_SEARCH_UPDATED");
    }

    private PartySnapshot snapshot(Party party, UUID viewerId, Instant now) {
        List<PartySnapshot.Member> roster = members.findByPartyIdOrderByJoinedAtAscIdAsc(party.id())
                .stream()
                .map(member -> {
                    PlayerProfile profile = member.simulated() ? null : profiles.findById(member.playerId()).orElse(null);
                    return new PartySnapshot.Member(
                            member.playerId(),
                            profile == null ? member.displayName() : profile.displayName(),
                            profile == null ? member.primaryRole() : profile.primaryRole(),
                            profile == null ? member.secondaryRole() : profile.secondaryRole(),
                            member.ready(),
                            member.simulated() || (profile != null && profile.online(now)),
                            member.simulated());
                })
                .toList();
        List<PartySnapshot.Invitation> pending = invitations.findByPartyIdOrderByCreatedAtDesc(party.id())
                .stream()
                .filter(invitation -> invitation.pendingAt(now))
                .map(invitation -> new PartySnapshot.Invitation(
                        invitation.id(), invitation.inviteeId(), invitation.inviteeDisplayName(),
                        invitation.status().name(), invitation.expiresAt()))
                .toList();
        return new PartySnapshot(
                party.id(),
                party.leaderId(),
                party.region(),
                party.leaderId().equals(viewerId),
                roster.size() >= 2 && roster.stream().allMatch(PartySnapshot.Member::ready),
                5,
                roster,
                pending);
    }

    private PartySnapshot announce(PartySnapshot snapshot, String type) {
        var recipients = new java.util.HashSet<UUID>();
        snapshot.members().forEach(member -> recipients.add(member.playerId()));
        snapshot.invitations().forEach(invitation -> recipients.add(invitation.inviteeId()));
        realtime.publishAfterCommit(Set.copyOf(recipients), type);
        return snapshot;
    }

    private Party currentParty(UUID playerId) {
        PartyMember membership = members.findByPlayerId(playerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Aucun groupe actif"));
        return parties.findById(membership.partyId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Groupe introuvable"));
    }

    private PartyInvitation invitationForPlayer(UUID playerId, UUID invitationId) {
        return invitations.findById(invitationId)
                .filter(invitation -> invitation.inviteeId().equals(playerId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Invitation introuvable"));
    }

    private static void requireLeader(Party party, UUID playerId) {
        if (!party.leaderId().equals(playerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Seul le chef du groupe peut faire cela");
        }
    }

    private static String normalizeRegion(String region) {
        if (region == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Région requise");
        String normalized = region.trim().toUpperCase(Locale.ROOT);
        if (!REGIONS.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Région non supportée");
        }
        return normalized;
    }

    private static String normalizeDisplayName(String displayName) {
        if (displayName == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pseudo requis");
        String normalized = displayName.trim().replaceAll("\\s+", " ");
        if (normalized.length() < 3 || normalized.length() > 24
                || !normalized.matches("[\\p{L}\\p{N}_. -]+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Format du pseudo invalide");
        }
        return normalized;
    }

    private static String firstAvailableRole(List<PartyMember> roster) {
        Set<String> assigned = roster.stream().map(PartyMember::primaryRole).collect(java.util.stream.Collectors.toSet());
        return ROLES.stream().filter(role -> !assigned.contains(role)).findFirst().orElse("MID");
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}

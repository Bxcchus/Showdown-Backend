package lol.pinkward.showdown.match;

import java.util.List;

record MatchDetail(
        MatchHistoryEntry summary,
        List<MatchParticipantDetail> teammates,
        List<MatchParticipantDetail> opponents) {}

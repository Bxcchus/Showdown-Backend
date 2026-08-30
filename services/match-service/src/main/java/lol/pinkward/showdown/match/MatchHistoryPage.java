package lol.pinkward.showdown.match;

import java.util.List;

record MatchHistoryPage(
        List<MatchHistoryEntry> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasPrevious,
        boolean hasNext) {}

package lol.pinkward.showdown.match;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lol.pinkward.showdown.contracts.PlayerRolePreference;
import org.springframework.stereotype.Service;

@Service
class RoleAssignmentService {

    Map<UUID, LaneRole> assign(
            List<UUID> teamPlayers,
            Map<UUID, PlayerRolePreference> preferences) {
        if (teamPlayers.size() != LaneRole.values().length) {
            throw new IllegalArgumentException("A team requires exactly five players");
        }

        List<List<LaneRole>> candidates = new ArrayList<>();
        permutations(new ArrayList<>(), EnumSet.allOf(LaneRole.class), candidates);
        List<LaneRole> best = null;
        AssignmentQuality bestQuality = null;
        for (List<LaneRole> candidate : candidates) {
            List<Boolean> primaryByQueuePriority = new ArrayList<>();
            List<Boolean> secondaryByQueuePriority = new ArrayList<>();
            for (int index = 0; index < teamPlayers.size(); index++) {
                PlayerRolePreference preference = preferences.get(teamPlayers.get(index));
                String assigned = candidate.get(index).name();
                primaryByQueuePriority.add(preference.primaryRole().equals(assigned));
                secondaryByQueuePriority.add(preference.secondaryRole().equals(assigned));
            }
            var quality = new AssignmentQuality(primaryByQueuePriority, secondaryByQueuePriority);
            if (bestQuality == null || quality.isBetterThan(bestQuality)) {
                bestQuality = quality;
                best = candidate;
            }
        }

        Map<UUID, LaneRole> result = new HashMap<>();
        for (int index = 0; index < teamPlayers.size(); index++) {
            result.put(teamPlayers.get(index), best.get(index));
        }
        return result;
    }

    private void permutations(
            List<LaneRole> selected,
            EnumSet<LaneRole> remaining,
            List<List<LaneRole>> result) {
        if (remaining.isEmpty()) {
            result.add(List.copyOf(selected));
            return;
        }
        for (LaneRole role : List.copyOf(remaining)) {
            selected.add(role);
            var next = EnumSet.copyOf(remaining);
            next.remove(role);
            permutations(selected, next, result);
            selected.removeLast();
        }
    }

    private record AssignmentQuality(
            List<Boolean> primaryByQueuePriority,
            List<Boolean> secondaryByQueuePriority) {

        boolean isBetterThan(AssignmentQuality other) {
            int primaryCount = count(primaryByQueuePriority);
            int otherPrimaryCount = count(other.primaryByQueuePriority);
            if (primaryCount != otherPrimaryCount) return primaryCount > otherPrimaryCount;

            int primaryPriority = comparePriority(primaryByQueuePriority, other.primaryByQueuePriority);
            if (primaryPriority != 0) return primaryPriority > 0;

            int secondaryCount = count(secondaryByQueuePriority);
            int otherSecondaryCount = count(other.secondaryByQueuePriority);
            if (secondaryCount != otherSecondaryCount) return secondaryCount > otherSecondaryCount;

            return comparePriority(secondaryByQueuePriority, other.secondaryByQueuePriority) > 0;
        }

        private static int count(List<Boolean> values) {
            return (int) values.stream().filter(Boolean::booleanValue).count();
        }

        private static int comparePriority(List<Boolean> left, List<Boolean> right) {
            for (int index = 0; index < left.size(); index++) {
                if (!left.get(index).equals(right.get(index))) return left.get(index) ? 1 : -1;
            }
            return 0;
        }
    }
}

package lol.pinkward.showdown.player;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class PlayerDirectoryControllerTest {

    @Test
    void publicDirectoryContractDoesNotExposeRiotIdentifiers() {
        assertThat(Arrays.stream(PlayerDirectoryController.Entry.class.getRecordComponents())
                .map(RecordComponent::getName))
                .containsExactly("playerId", "displayName", "riotLinked")
                .doesNotContain("riotGameName", "riotTagLine", "riotId", "riotPuuid");
    }
}

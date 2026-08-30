package lol.pinkward.showdown.match;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class LobbyCredentialServiceTest {

    @Test
    void createsAStableNameAndAnEncryptedRandomPassword() {
        var service = new LobbyCredentialService("test-lobby-credential-key-change-me");
        UUID matchId = UUID.fromString("12345678-1234-1234-1234-123456789abc");

        var credentials = service.create(matchId);
        String password = service.decrypt(credentials.encryptedPassword());

        assertThat(credentials.name()).isEqualTo("SWD-12345678");
        assertThat(password).matches("[A-HJ-NP-Z2-9]{8}");
        assertThat(credentials.encryptedPassword()).doesNotContain(password);
    }
}

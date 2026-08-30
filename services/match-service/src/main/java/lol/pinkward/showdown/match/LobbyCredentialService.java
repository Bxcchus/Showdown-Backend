package lol.pinkward.showdown.match;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
class LobbyCredentialService {

    private static final String PASSWORD_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // gitleaks:allow
    private static final int IV_LENGTH = 12;
    private static final int GCM_TAG_BITS = 128;

    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec key;

    LobbyCredentialService(@Value("${pinkward.lobby.credential-key}") String credentialKey) {
        if (credentialKey == null || credentialKey.length() < 24) {
            throw new IllegalArgumentException("Lobby credential key must contain at least 24 characters");
        }
        try {
            this.key = new SecretKeySpec(
                    MessageDigest.getInstance("SHA-256")
                            .digest(credentialKey.getBytes(StandardCharsets.UTF_8)),
                    "AES");
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Could not initialize lobby credential encryption", exception);
        }
    }

    LobbyCredentials create(UUID matchId) {
        String name = "SWD-" + matchId.toString().substring(0, 8).toUpperCase();
        StringBuilder password = new StringBuilder(8);
        for (int index = 0; index < 8; index++) {
            password.append(PASSWORD_ALPHABET.charAt(random.nextInt(PASSWORD_ALPHABET.length())));
        }
        return new LobbyCredentials(name, encrypt(password.toString()));
    }

    String decrypt(String encryptedPassword) {
        if (encryptedPassword == null) return null;
        try {
            byte[] combined = Base64.getUrlDecoder().decode(encryptedPassword);
            ByteBuffer buffer = ByteBuffer.wrap(combined);
            byte[] iv = new byte[IV_LENGTH];
            buffer.get(iv);
            byte[] ciphertext = new byte[buffer.remaining()];
            buffer.get(ciphertext);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalStateException("Could not decrypt lobby password", exception);
        }
    }

    private String encrypt(String password) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(password.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(ByteBuffer.allocate(iv.length + ciphertext.length)
                            .put(iv)
                            .put(ciphertext)
                            .array());
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Could not encrypt lobby password", exception);
        }
    }

    record LobbyCredentials(String name, String encryptedPassword) {}
}

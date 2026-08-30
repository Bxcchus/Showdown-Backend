package lol.pinkward.showdown.identity;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import jakarta.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
final class PersistentJwkSource implements JWKSource<SecurityContext> {

    private final Path keyStore;
    private final Duration rotationAge;
    private final Duration overlap;
    private volatile JWKSet keys;
    private volatile RSAKey active;

    PersistentJwkSource(
            @Value("${pinkward.jwt.key-store}") Path keyStore,
            @Value("${pinkward.jwt.rotation-age}") Duration rotationAge,
            @Value("${pinkward.jwt.overlap}") Duration overlap) {
        this.keyStore = keyStore;
        this.rotationAge = rotationAge;
        this.overlap = overlap;
    }

    @PostConstruct
    synchronized void initialize() {
        try {
            if (Files.exists(keyStore)) {
                restrictToOwner(keyStore);
                keys = JWKSet.parse(Files.readString(keyStore));
                active = keys.getKeys().stream()
                        .filter(RSAKey.class::isInstance)
                        .map(RSAKey.class::cast)
                        .filter(RSAKey::isPrivate)
                        .max(Comparator.comparing(PersistentJwkSource::issuedAt))
                        .orElseThrow(() -> new IllegalStateException("JWT key store has no private RSA key"));
            } else {
                active = generate(Instant.now());
                keys = new JWKSet(active);
                persist();
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Could not load the persistent JWT key store", exception);
        }
    }

    @Scheduled(fixedDelayString = "${pinkward.jwt.rotation-check}")
    synchronized void rotateIfDue() {
        Instant now = Instant.now();
        if (now.isBefore(issuedAt(active).plus(rotationAge))) return;
        RSAKey next = generate(now);
        Instant cutoff = now.minus(overlap);
        List<JWK> retained = new ArrayList<>();
        retained.add(next);
        keys.getKeys().stream()
                .filter(key -> !key.getKeyID().equals(next.getKeyID()))
                .filter(key -> !issuedAt(key).isBefore(cutoff))
                .forEach(retained::add);
        active = next;
        keys = new JWKSet(retained);
        persist();
    }

    String activeKeyId() {
        return active.getKeyID();
    }

    @Override
    public List<JWK> get(JWKSelector selector, SecurityContext context) throws KeySourceException {
        return selector.select(keys);
    }

    private void persist() {
        try {
            Files.createDirectories(keyStore.toAbsolutePath().getParent());
            Path temporary = keyStore.resolveSibling(keyStore.getFileName() + ".tmp");
            Files.writeString(temporary, keys.toString(false),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            restrictToOwner(temporary);
            try {
                Files.move(temporary, keyStore, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                Files.move(temporary, keyStore, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Could not persist the JWT key store", exception);
        }
    }

    private static void restrictToOwner(Path path) throws java.io.IOException {
        try {
            Files.setPosixFilePermissions(path, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // The production image uses a POSIX filesystem. Windows development
            // relies on the host ACL and the isolated container volume instead.
        }
    }

    private static RSAKey generate(Instant now) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(3072);
            var pair = generator.generateKeyPair();
            return new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                    .privateKey((RSAPrivateKey) pair.getPrivate())
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    .keyID(UUID.randomUUID().toString())
                    .issueTime(Date.from(now))
                    .build();
        } catch (Exception exception) {
            throw new IllegalStateException("Could not generate an RSA signing key", exception);
        }
    }

    private static Instant issuedAt(JWK key) {
        Date issueTime = key.getIssueTime();
        return issueTime == null ? Instant.EPOCH : issueTime.toInstant();
    }
}

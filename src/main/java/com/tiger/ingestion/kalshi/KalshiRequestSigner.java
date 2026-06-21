package com.tiger.ingestion.kalshi;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.Security;
import java.security.Signature;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;
import java.time.Instant;
import java.util.Base64;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

final class KalshiRequestSigner {
    private static final int SHA256_DIGEST_LENGTH = 32;
    private static final PSSParameterSpec KALSHI_PSS_SPEC =
            new PSSParameterSpec(
                    "SHA-256",
                    "MGF1",
                    MGF1ParameterSpec.SHA256,
                    SHA256_DIGEST_LENGTH,
                    PSSParameterSpec.TRAILER_FIELD_BC);

    private final String keyId;
    private final PrivateKey privateKey;

    KalshiRequestSigner(String keyId, String privateKeyPath) {
        if (keyId == null || keyId.isBlank()) {
            throw new IllegalStateException("KALSHI_KEY_ID / tiger.kalshi.key-id is required");
        }
        if (privateKeyPath == null || privateKeyPath.isBlank()) {
            throw new IllegalStateException(
                    "KALSHI_PRIVATE_KEY_PATH / tiger.kalshi.private-key-path is required");
        }
        this.keyId = keyId;
        this.privateKey = loadPrivateKey(resolveKeyPath(privateKeyPath));
    }

    HttpHeaders sign(String method, String signedPath) {
        String timestampMs = String.valueOf(Instant.now().toEpochMilli());
        String message = timestampMs + method.toUpperCase() + signedPath;
        try {
            Signature signature = createPssSigner();
            signature.initSign(privateKey);
            signature.update(message.getBytes(StandardCharsets.UTF_8));
            String encoded = Base64.getEncoder().encodeToString(signature.sign());

            HttpHeaders headers = new HttpHeaders();
            headers.set("KALSHI-ACCESS-KEY", keyId);
            headers.set("KALSHI-ACCESS-TIMESTAMP", timestampMs);
            headers.set("KALSHI-ACCESS-SIGNATURE", encoded);
            headers.setContentType(MediaType.APPLICATION_JSON);
            return headers;
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Failed to sign Kalshi request", ex);
        }
    }

    /**
     * Kalshi uses RSA-PSS + SHA-256 with salt length = digest length (32 bytes).
     * Oracle JDK 21 exposes this as {@code RSASSA-PSS}, not {@code SHA256withRSA/PSS}.
     */
    private static Signature createPssSigner() throws GeneralSecurityException {
        try {
            Signature signature = Signature.getInstance("RSASSA-PSS");
            signature.setParameter(KALSHI_PSS_SPEC);
            return signature;
        } catch (NoSuchAlgorithmException ignored) {
            ensureBouncyCastleProvider();
            Signature signature =
                    Signature.getInstance("SHA256withRSA/PSS", BouncyCastleProvider.PROVIDER_NAME);
            signature.setParameter(KALSHI_PSS_SPEC);
            return signature;
        }
    }

    private static void ensureBouncyCastleProvider() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private static Path resolveKeyPath(String privateKeyPath) {
        Path path = Path.of(privateKeyPath);
        if (!path.isAbsolute()) {
            path = Path.of(System.getProperty("user.dir")).resolve(path);
        }
        return path.normalize();
    }

    private static PrivateKey loadPrivateKey(Path path) {
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("Kalshi private key file not found: " + path);
        }
        try (PEMParser parser = new PEMParser(Files.newBufferedReader(path))) {
            Object parsed = parser.readObject();
            JcaPEMKeyConverter converter = new JcaPEMKeyConverter();
            if (parsed instanceof PEMKeyPair pemKeyPair) {
                return converter.getPrivateKey(pemKeyPair.getPrivateKeyInfo());
            }
            if (parsed instanceof PrivateKeyInfo privateKeyInfo) {
                return converter.getPrivateKey(privateKeyInfo);
            }
            if (parsed instanceof PrivateKey privateKey) {
                return privateKey;
            }
            throw new IllegalStateException("Unsupported PEM key format in " + path);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read Kalshi private key from " + path, ex);
        }
    }

}

package com.tiger.ingestion.kalshi;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KalshiRequestSignerTest {
    @Test
    void rsassaPssAlgorithmIsAvailableOnThisJdk() throws Exception {
        Signature signature = Signature.getInstance("RSASSA-PSS");
        signature.setParameter(
                new PSSParameterSpec(
                        "SHA-256",
                        "MGF1",
                        MGF1ParameterSpec.SHA256,
                        32,
                        PSSParameterSpec.TRAILER_FIELD_BC));
        assertThat(signature.getAlgorithm()).isEqualTo("RSASSA-PSS");
    }

    @Test
    void signsRequestWithGeneratedKey(@TempDir Path tempDir) throws Exception {
        Path keyPath = tempDir.resolve("test.key");
        var keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        var pair = keyGen.generateKeyPair();

        String pem =
                "-----BEGIN PRIVATE KEY-----\n"
                        + java.util.Base64.getMimeEncoder(64, "\n".getBytes())
                                .encodeToString(pair.getPrivate().getEncoded())
                        + "\n-----END PRIVATE KEY-----\n";
        Files.writeString(keyPath, pem);

        KalshiRequestSigner signer = new KalshiRequestSigner("test-key-id", keyPath.toString());
        var headers = signer.sign("GET", "/trade-api/v2/series");

        assertThat(headers.getFirst("KALSHI-ACCESS-KEY")).isEqualTo("test-key-id");
        assertThat(headers.getFirst("KALSHI-ACCESS-TIMESTAMP")).isNotBlank();
        assertThat(headers.getFirst("KALSHI-ACCESS-SIGNATURE")).isNotBlank();
    }
}

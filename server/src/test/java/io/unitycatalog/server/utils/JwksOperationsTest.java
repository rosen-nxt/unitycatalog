package io.unitycatalog.server.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.auth0.jwk.Jwk;
import com.auth0.jwk.JwkProvider;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import io.unitycatalog.server.exception.OAuthInvalidClientException;
import io.unitycatalog.server.exception.OAuthInvalidRequestException;
import io.unitycatalog.server.security.SecurityContext;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Exercises signature and algorithm checks using an Entra-shaped RSA signing key. */
class JwksOperationsTest {
  private static final String ISSUER =
      "https://login.microsoftonline.com/11111111-1111-1111-1111-111111111111/v2.0";
  private static KeyPair keyPair;

  @BeforeAll
  static void createKey() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    keyPair = generator.generateKeyPair();
  }

  @Test
  void verifiesRsaTokensAndRejectsInvalidSignaturesExpiryAndAlgorithms() throws Exception {
    Jwk jwk = mock(Jwk.class);
    when(jwk.getType()).thenReturn("RSA");
    when(jwk.getPublicKey()).thenReturn(keyPair.getPublic());
    JwkProvider provider = mock(JwkProvider.class);
    when(provider.get("entra-key")).thenReturn(jwk);
    JwksOperations operations = spy(new JwksOperations(mock(SecurityContext.class)));
    doReturn(provider).when(operations).loadJwkProvider(ISSUER);

    Algorithm signing =
        Algorithm.RSA256((RSAPublicKey) keyPair.getPublic(), (RSAPrivateKey) keyPair.getPrivate());
    var verifier = operations.verifierForIssuerAndKey(ISSUER, "entra-key", "RS256");
    String valid = JWT.create().withIssuer(ISSUER).withSubject("human@example.com").sign(signing);
    assertThat(verifier.verify(valid).getSubject()).isEqualTo("human@example.com");

    KeyPair foreignKey = KeyPairGenerator.getInstance("RSA").generateKeyPair();
    Algorithm foreignSigning =
        Algorithm.RSA256(
            (RSAPublicKey) foreignKey.getPublic(), (RSAPrivateKey) foreignKey.getPrivate());
    for (String invalid :
        new String[] {
          JWT.create().withIssuer(ISSUER).sign(foreignSigning),
          JWT.create()
              .withIssuer(ISSUER)
              .withExpiresAt(Date.from(Instant.now().minusSeconds(60)))
              .sign(signing),
          JWT.create().withIssuer("https://other.example.com").sign(signing),
          JWT.create().withIssuer(ISSUER).sign(Algorithm.none())
        }) {
      assertThatThrownBy(() -> verifier.verify(invalid))
          .isInstanceOf(JWTVerificationException.class);
    }
    assertThatThrownBy(() -> operations.verifierForIssuerAndKey(ISSUER, "entra-key", "HS256"))
        .isInstanceOf(OAuthInvalidClientException.class)
        .hasMessage("Unsupported RSA algorithm: HS256");
  }

  @Test
  void verifiesInternalTokensWithTheConfiguredSigningKey() throws Exception {
    SecurityContext securityContext = mock(SecurityContext.class);
    Algorithm signing =
        Algorithm.RSA512((RSAPublicKey) keyPair.getPublic(), (RSAPrivateKey) keyPair.getPrivate());
    when(securityContext.getKeyId()).thenReturn("internal-key");
    when(securityContext.getAlgorithm()).thenReturn(signing);
    JwksOperations operations = new JwksOperations(securityContext);

    String valid =
        JWT.create()
            .withIssuer(SecurityContext.Issuers.INTERNAL)
            .withKeyId("internal-key")
            .withSubject("human@example.com")
            .sign(signing);

    var verifier =
        operations.verifierForIssuerAndKey(
            SecurityContext.Issuers.INTERNAL, "internal-key", "RS512");
    assertThat(verifier.verify(valid).getSubject()).isEqualTo("human@example.com");
    assertThatThrownBy(
            () ->
                operations.verifierForIssuerAndKey(
                    SecurityContext.Issuers.INTERNAL, "unknown-key", "RS512"))
        .isInstanceOf(OAuthInvalidRequestException.class)
        .hasMessage("Unknown internal key id");
    assertThatThrownBy(
            () ->
                operations.verifierForIssuerAndKey(
                    SecurityContext.Issuers.INTERNAL, "internal-key", "RS256"))
        .isInstanceOf(OAuthInvalidClientException.class)
        .hasMessage("Unsupported internal algorithm: RS256");
  }
}

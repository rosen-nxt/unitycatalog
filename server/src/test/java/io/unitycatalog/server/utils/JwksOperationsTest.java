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
import com.sun.net.httpserver.HttpServer;
import io.unitycatalog.server.exception.BaseException;
import io.unitycatalog.server.exception.ErrorCode;
import io.unitycatalog.server.exception.OAuthInvalidClientException;
import io.unitycatalog.server.exception.OAuthInvalidRequestException;
import io.unitycatalog.server.security.SecurityContext;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JwksOperationsTest {
  private static final String ISSUER =
      "https://login.microsoftonline.com/11111111-1111-1111-1111-111111111111/v2.0";
  private static KeyPair keyPair;

  private HttpServer issuerServer;
  private String issuer;
  private int discoveryStatus;
  private String discoveryContentType;
  private String discoveryBody;

  private final JwksOperations jwksOperations = new JwksOperations(null);

  @BeforeAll
  static void createKey() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    keyPair = generator.generateKeyPair();
  }

  @BeforeEach
  void startIssuerServer() throws Exception {
    issuerServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    // Daemon threads, so the forked test JVM can exit even if stop() leaves threads behind.
    issuerServer.setExecutor(
        Executors.newCachedThreadPool(
            r -> {
              Thread t = new Thread(r);
              t.setDaemon(true);
              return t;
            }));
    issuerServer.createContext(
        "/.well-known/openid-configuration",
        exchange -> {
          byte[] body = discoveryBody.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", discoveryContentType);
          exchange.sendResponseHeaders(discoveryStatus, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    issuerServer.createContext(
        "/jwks",
        exchange -> {
          byte[] body = "RBAC: access denied".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "text/plain");
          exchange.sendResponseHeaders(403, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    issuerServer.start();
    issuer = "http://127.0.0.1:" + issuerServer.getAddress().getPort();
  }

  @AfterEach
  void stopIssuerServer() {
    issuerServer.stop(0);
  }

  @Test
  void loadJwkProvider_reportsStatusAndUrlWhenDiscoveryRequestIsRejected() {
    // What Istio returns when an AuthorizationPolicy denies the request.
    respondWith(403, "text/plain", "RBAC: access denied");

    assertThatThrownBy(() -> jwksOperations.loadJwkProvider(issuer))
        .isInstanceOf(OAuthInvalidRequestException.class)
        .hasMessageContaining(issuer + "/.well-known/openid-configuration")
        .hasMessageContaining("HTTP 403")
        .hasMessageNotContaining("RBAC")
        .extracting(e -> ((BaseException) e).getErrorCode())
        .isEqualTo(ErrorCode.INTERNAL);
  }

  @Test
  void verifierForIssuerAndKey_reportsKeyAndIssuerWhenSigningKeyFetchIsRejected() {
    respondWith(
        200,
        "application/json",
        String.format("{\"issuer\":\"%s\",\"jwks_uri\":\"%s/jwks\"}", issuer, issuer));

    assertThatThrownBy(() -> jwksOperations.verifierForIssuerAndKey(issuer, "key-1", "RS256"))
        .isInstanceOf(OAuthInvalidRequestException.class)
        .hasMessage("Could not get signing key 'key-1' for issuer " + issuer)
        .extracting(e -> ((BaseException) e).getErrorCode())
        .isEqualTo(ErrorCode.INTERNAL);
  }

  @Test
  void loadJwkProvider_reportsUrlWhenDiscoveryDocumentIsNotJson() {
    respondWith(200, "text/html", "<html>login</html>");

    assertThatThrownBy(() -> jwksOperations.loadJwkProvider(issuer))
        .isInstanceOf(OAuthInvalidRequestException.class)
        .hasMessageContaining(issuer + "/.well-known/openid-configuration")
        .hasMessageContaining("not valid JSON");
  }

  @Test
  void loadJwkProvider_reportsBothIssuersWhenDiscoveryIssuerDiffers() {
    respondWith(200, "application/json", "{\"issuer\":\"https://other.example\"}");

    assertThatThrownBy(() -> jwksOperations.loadJwkProvider(issuer))
        .isInstanceOf(OAuthInvalidRequestException.class)
        .hasMessageContaining("'" + issuer + "'")
        .hasMessageContaining("'https://other.example'")
        .extracting(e -> ((BaseException) e).getErrorCode())
        .isEqualTo(ErrorCode.ABORTED);
  }

  @Test
  void loadJwkProvider_reportsMismatchWhenDiscoveryIssuerIsMissing() {
    respondWith(200, "application/json", "{\"jwks_uri\":\"https://other.example/jwks\"}");

    assertThatThrownBy(() -> jwksOperations.loadJwkProvider(issuer))
        .isInstanceOf(OAuthInvalidRequestException.class)
        .hasMessageContaining("doesn't match configuration issuer 'null'");
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

  private void respondWith(int status, String contentType, String body) {
    discoveryStatus = status;
    discoveryContentType = contentType;
    discoveryBody = body;
  }
}

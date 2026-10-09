package io.unitycatalog.server.service.credential.aws;

import static io.unitycatalog.server.service.credential.CredentialContext.Privilege.SELECT;
import static io.unitycatalog.server.service.credential.CredentialContext.Privilege.UPDATE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import com.azure.core.credential.TokenRequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.unitycatalog.server.model.AwsIamRoleResponse;
import io.unitycatalog.server.persist.dao.CredentialDAO;
import io.unitycatalog.server.service.credential.CredentialContext;
import io.unitycatalog.server.service.credential.CloudCredentialVendor;
import io.unitycatalog.server.utils.NormalizedURL;
import io.unitycatalog.server.utils.ServerProperties;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.sts.model.Credentials;
import software.amazon.awssdk.services.sts.model.StsException;

class SeaweedFsOidcCredentialGeneratorTest {
  private static final String ROLE = "arn:aws:iam::role/UnityCatalogSeaweedBrokerRole";
  private static final String SCOPE = "api://seaweed-api/.default";
  private final AtomicReference<Map<String, String>> form = new AtomicReference<>();
  private final AtomicReference<String> query = new AtomicReference<>();
  private final AtomicReference<TokenRequestContext> tokenRequest = new AtomicReference<>();
  private HttpServer server;
  private int status = 200;
  private String response;

  @BeforeEach
  void setUp() throws Exception {
    response = """
        <AssumeRoleWithWebIdentityResponse xmlns="https://sts.amazonaws.com/doc/2011-06-15/">
          <AssumeRoleWithWebIdentityResult><Credentials>
            <AccessKeyId>temporary-key</AccessKeyId>
            <SecretAccessKey>temporary-secret</SecretAccessKey>
            <SessionToken>temporary-session</SessionToken>
            <Expiration>%s</Expiration>
          </Credentials></AssumeRoleWithWebIdentityResult>
          <ResponseMetadata><RequestId>test-request</RequestId></ResponseMetadata>
        </AssumeRoleWithWebIdentityResponse>
        """.formatted(Instant.now().plusSeconds(900));
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      assertThat(exchange.getRequestMethod()).isEqualTo("POST");
      assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isNull();
      query.set(exchange.getRequestURI().getRawQuery());
      form.set(decodeForm(new String(
          exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
      byte[] body = response.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "text/xml");
      exchange.sendResponseHeaders(status, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    server.start();
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  private S3StorageConfig.S3StorageConfigBuilder config() {
    return S3StorageConfig.builder()
        .bucketPath("s3://rofm-storage")
        .region("us-east-1")
        .awsRoleArn(ROLE)
        .stsEndpoint("http://127.0.0.1:" + server.getAddress().getPort())
        .oidcScope(SCOPE);
  }

  private TokenCredential tokenCredential() {
    return context -> {
      tokenRequest.set(context);
      return Mono.just(new AccessToken("entra-access-token", OffsetDateTime.now().plusHours(1)));
    };
  }

  private CredentialContext context(Set<CredentialContext.Privilege> privileges) {
    return CredentialContext.create(
        NormalizedURL.from("s3://rofm-storage/catalog/schema/table"), privileges, Optional.empty());
  }

  @Test
  void exchangesEntraTokenAndDownscopesReads() throws Exception {
    try (var generator =
        new SeaweedFsOidcCredentialGenerator(config().build(), tokenCredential())) {
      Credentials credentials = generator.generate(context(Set.of(SELECT)));
      assertThat(tokenRequest.get().getScopes()).containsExactly(SCOPE);
      assertThat(query.get()).isEqualTo("Action=AssumeRoleWithWebIdentity");
      assertThat(form.get())
          .containsEntry("Action", "AssumeRoleWithWebIdentity")
          .containsEntry("WebIdentityToken", "entra-access-token")
          .containsEntry("RoleArn", ROLE)
          .containsEntry("DurationSeconds", "900");
      assertThat(form.get().get("RoleSessionName")).startsWith("uc-");
      String policy = form.get().get("Policy");
      assertThat(new ObjectMapper().readTree(policy).path("Statement").size()).isEqualTo(2);
      assertThat(policy).contains("catalog/schema/table/*", "s3:GetO*", "s3:prefix")
          .doesNotContain("s3:PutO*", "s3:DeleteO*");
      assertThat(credentials.accessKeyId()).isEqualTo("temporary-key");
      assertThat(credentials.secretAccessKey()).isEqualTo("temporary-secret");
      assertThat(credentials.sessionToken()).isEqualTo("temporary-session");
      assertThat(credentials.expiration()).isAfter(Instant.now());
    }
  }

  @Test
  void downscopesWritesAndSupportsMatchingExternalLocationRole() {
    CredentialDAO dao = mock(CredentialDAO.class);
    when(dao.getAwsIamRoleResponse()).thenReturn(new AwsIamRoleResponse().roleArn(ROLE));
    var ctx = CredentialContext.create(
        NormalizedURL.from("s3://rofm-storage/table"), Set.of(SELECT, UPDATE), Optional.of(dao));
    try (var generator =
        new SeaweedFsOidcCredentialGenerator(config().build(), tokenCredential())) {
      generator.generate(ctx);
      assertThat(form.get().get("Policy"))
          .contains("rofm-storage/table/*", "s3:PutO*", "s3:DeleteO*", "s3:*Multipart*");
    }
  }

  @Test
  void rejectsMismatchedExternalLocationRoleBeforeRequestingToken() {
    CredentialDAO dao = mock(CredentialDAO.class);
    when(dao.getAwsIamRoleResponse())
        .thenReturn(new AwsIamRoleResponse().roleArn("another-role"));
    var ctx = CredentialContext.create(
        NormalizedURL.from("s3://rofm-storage/table"), Set.of(SELECT), Optional.of(dao));
    try (var generator =
        new SeaweedFsOidcCredentialGenerator(config().build(), tokenCredential())) {
      assertThatThrownBy(() -> generator.generate(ctx))
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(tokenRequest.get()).isNull();
      assertThat(form.get()).isNull();
    }
  }

  @Test
  void rejectsExpiredEntraTokenAndUnknownPrivileges() {
    TokenCredential expired = ignored -> Mono.just(
        new AccessToken("expired", OffsetDateTime.now().minusMinutes(1)));
    try (var generator = new SeaweedFsOidcCredentialGenerator(config().build(), expired)) {
      assertThatThrownBy(() -> generator.generate(context(Set.of(SELECT))))
          .isInstanceOf(IllegalStateException.class);
      assertThatThrownBy(() -> generator.generate(context(Set.of())))
          .isInstanceOf(org.apache.iceberg.exceptions.NotAuthorizedException.class);
      assertThat(form.get()).isNull();
    }
  }

  @Test
  void failsClosedWhenStsRejectsExchange() {
    status = 403;
    response = """
        <ErrorResponse xmlns="https://sts.amazonaws.com/doc/2011-06-15/">
          <Error><Type>Sender</Type><Code>AccessDenied</Code><Message>Denied</Message></Error>
          <RequestId>test-request</RequestId>
        </ErrorResponse>
        """;
    try (var generator =
        new SeaweedFsOidcCredentialGenerator(config().build(), tokenCredential())) {
      assertThatThrownBy(() -> generator.generate(context(Set.of(SELECT))))
          .isInstanceOf(StsException.class);
    }
  }

  @Test
  void requiresExpirationForClientRefresh() {
    response = response.replaceAll("<Expiration>.*?</Expiration>", "");
    try (var generator =
        new SeaweedFsOidcCredentialGenerator(config().build(), tokenCredential())) {
      assertThatThrownBy(() -> generator.generate(context(Set.of(SELECT))))
          .isInstanceOf(IllegalStateException.class);
    }
  }

  @Test
  void vendorSelectsSeaweedForExternalLocationsAndPreservesExpiration() {
    ServerProperties properties = mock(ServerProperties.class);
    when(properties.getS3Configurations()).thenReturn(
        Map.of(NormalizedURL.from("s3://rofm-storage"), config().build()));
    CredentialDAO dao = mock(CredentialDAO.class);
    var ctx = CredentialContext.create(
        NormalizedURL.from("s3://rofm-storage/table"), Set.of(SELECT), Optional.of(dao));
    Instant expiration = Instant.now().plusSeconds(900);
    Credentials credentials = Credentials.builder().accessKeyId("key").secretAccessKey("secret")
        .sessionToken("session").expiration(expiration).build();
    try (MockedConstruction<SeaweedFsOidcCredentialGenerator> constructed =
        Mockito.mockConstruction(SeaweedFsOidcCredentialGenerator.class,
            (mock, ignored) -> when(mock.generate(any())).thenReturn(credentials))) {
      AwsCredentialVendor vendor = new AwsCredentialVendor(properties, () -> {
        throw new IllegalStateException("SeaweedFS must not use the AWS STS generator");
      });
      var result = new CloudCredentialVendor(vendor, null, null).vendCredential(ctx);
      assertThat(constructed.constructed()).hasSize(1);
      verify(constructed.constructed().get(0)).generate(ctx);
      assertThat(result.getAwsTempCredentials().getSessionToken()).isEqualTo("session");
      assertThat(result.getExpirationTime()).isEqualTo(expiration.toEpochMilli());
    }
  }

  @Test
  void validatesConfiguration() {
    assertThatThrownBy(() -> new SeaweedFsOidcCredentialGenerator(
        config().stsEndpoint("http://seaweed.example.com").build(), tokenCredential()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SeaweedFsOidcCredentialGenerator(
        config().oidcScope(null).build(), tokenCredential()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SeaweedFsOidcCredentialGenerator(
        config().sessionDurationSeconds(899).build(), tokenCredential()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SeaweedFsOidcCredentialGenerator(
        config().sessionToken("static-token").build(), tokenCredential()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void parsesPerBucketOidcConfiguration() {
    Properties properties = new Properties();
    properties.setProperty("s3.bucketPath.0", "s3://rofm-storage");
    properties.setProperty("s3.region.0", "us-east-1");
    properties.setProperty("s3.awsRoleArn.0", ROLE);
    properties.setProperty("s3.stsEndpoint.0", "https://seaweed.example.com");
    properties.setProperty("s3.oidcScope.0", SCOPE);
    properties.setProperty("s3.sessionDurationSeconds.0", "1800");
    S3StorageConfig parsed = new ServerProperties(properties).getS3Configurations()
        .get(NormalizedURL.from("s3://rofm-storage"));
    assertThat(parsed.getStsEndpoint()).isEqualTo("https://seaweed.example.com");
    assertThat(parsed.getOidcScope()).isEqualTo(SCOPE);
    assertThat(parsed.getSessionDurationSeconds()).isEqualTo(1800);
    properties.remove("s3.sessionDurationSeconds.0");
    assertThat(new ServerProperties(properties).getS3Configurations()
        .get(NormalizedURL.from("s3://rofm-storage")).getSessionDurationSeconds()).isEqualTo(900);
    properties.remove("s3.awsRoleArn.0");
    assertThatThrownBy(() -> new ServerProperties(properties).getS3Configurations())
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static Map<String, String> decodeForm(String body) {
    Map<String, String> fields = new HashMap<>();
    for (String field : body.split("&")) {
      String[] pair = field.split("=", 2);
      fields.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
          URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
    }
    return fields;
  }
}

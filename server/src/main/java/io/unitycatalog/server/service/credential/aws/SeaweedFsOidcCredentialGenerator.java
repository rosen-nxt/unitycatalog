package io.unitycatalog.server.service.credential.aws;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import com.azure.core.credential.TokenRequestContext;
import com.azure.identity.DefaultAzureCredentialBuilder;
import io.unitycatalog.server.service.credential.CredentialContext;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.http.ExecutableHttpRequest;
import software.amazon.awssdk.http.HttpExecuteRequest;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.AssumeRoleWithWebIdentityRequest;
import software.amazon.awssdk.services.sts.model.Credentials;
import software.amazon.awssdk.utils.AttributeMap;

/** Vends path-scoped SeaweedFS sessions using the server's Azure workload identity. */
public class SeaweedFsOidcCredentialGenerator implements AwsCredentialGenerator, AutoCloseable {
  private final S3StorageConfig config;
  private final TokenCredential tokenCredential;
  private final StsClient stsClient;

  public SeaweedFsOidcCredentialGenerator(S3StorageConfig config) {
    this(config, new DefaultAzureCredentialBuilder().build());
  }

  SeaweedFsOidcCredentialGenerator(S3StorageConfig config, TokenCredential tokenCredential) {
    validateConfig(config);
    this.config = config;
    this.tokenCredential = tokenCredential;
    this.stsClient =
        StsClient.builder()
            .region(Region.of(config.getRegion()))
            .endpointOverride(URI.create(config.getStsEndpoint()))
            .credentialsProvider(AnonymousCredentialsProvider.create())
            .httpClientBuilder(new GatewayHttpClientBuilder())
            .overrideConfiguration(builder -> builder.apiCallTimeout(Duration.ofSeconds(30)))
            .build();
  }

  private static void validateConfig(S3StorageConfig config) {
    if (config.getOidcScope() == null
        || config.getOidcScope().isBlank()
        || config.getAwsRoleArn() == null
        || config.getAwsRoleArn().isBlank()
        || config.getRegion() == null
        || config.getRegion().isBlank()) {
      throw new IllegalArgumentException(
          "SeaweedFS STS requires an OIDC scope, role ARN and region");
    }
    URI endpoint = URI.create(config.getStsEndpoint());
    boolean loopback =
        "localhost".equals(endpoint.getHost()) || "127.0.0.1".equals(endpoint.getHost());
    if (endpoint.getHost() == null
        || endpoint.getUserInfo() != null
        || endpoint.getQuery() != null
        || endpoint.getFragment() != null
        || !("https".equals(endpoint.getScheme())
            || (loopback && "http".equals(endpoint.getScheme())))
        || !(endpoint.getPath().isEmpty() || "/".equals(endpoint.getPath()))) {
      throw new IllegalArgumentException(
          "SeaweedFS STS endpoint must be an HTTPS origin (HTTP allowed only on loopback)");
    }
    if (config.getSessionDurationSeconds() < 900 || config.getSessionDurationSeconds() > 43200) {
      throw new IllegalArgumentException(
          "SeaweedFS STS session duration must be between 900 and 43200 seconds");
    }
    if (config.getAccessKey() != null
        || config.getSecretKey() != null
        || config.getSessionToken() != null
        || config.getCredentialGenerator() != null) {
      throw new IllegalArgumentException(
          "SeaweedFS OIDC cannot be combined with static keys or a custom generator");
    }
  }

  @Override
  public Credentials generate(CredentialContext ctx) {
    ctx.getCredentialDAO()
        .ifPresent(
            credential -> {
              if (credential.getAwsIamRoleResponse() == null
                  || !config
                      .getAwsRoleArn()
                      .equals(credential.getAwsIamRoleResponse().getRoleArn())) {
                throw new IllegalArgumentException(
                    "Storage credential role does not match the configured SeaweedFS broker role");
              }
            });
    // Always downscope after UC authorization; never return an unscoped broker session.
    String policy = AwsPolicyGenerator.generatePolicy(ctx.getPrivileges(), ctx.getLocations());
    AccessToken token =
        tokenCredential
            .getToken(new TokenRequestContext().addScopes(config.getOidcScope()))
            .block(Duration.ofSeconds(30));
    if (token == null || token.isExpired() || token.getToken().isBlank()) {
      throw new IllegalStateException("Could not obtain a valid Entra access token for SeaweedFS");
    }
    Credentials credentials =
        stsClient
            .assumeRoleWithWebIdentity(
                AssumeRoleWithWebIdentityRequest.builder()
                    .roleArn(config.getAwsRoleArn())
                    .roleSessionName("uc-" + UUID.randomUUID())
                    .durationSeconds(config.getSessionDurationSeconds())
                    .webIdentityToken(token.getToken())
                    .policy(policy)
                    .build())
            .credentials();
    if (credentials == null
        || credentials.expiration() == null
        || !credentials.expiration().isAfter(Instant.now())
        || credentials.accessKeyId() == null
        || credentials.accessKeyId().isBlank()
        || credentials.secretAccessKey() == null
        || credentials.secretAccessKey().isBlank()
        || credentials.sessionToken() == null
        || credentials.sessionToken().isBlank()) {
      throw new IllegalStateException("SeaweedFS STS returned incomplete or expired credentials");
    }
    return credentials;
  }

  // Add Action at the transport boundary: the AWS Query protocol otherwise moves
  // URL parameters into the POST body, even when added by an execution interceptor.
  private static class GatewayHttpClientBuilder
      implements SdkHttpClient.Builder<GatewayHttpClientBuilder> {
    @Override
    public SdkHttpClient buildWithDefaults(AttributeMap defaults) {
      SdkHttpClient delegate = ApacheHttpClient.builder().buildWithDefaults(defaults);
      return new SdkHttpClient() {
        @Override
        public ExecutableHttpRequest prepareRequest(HttpExecuteRequest request) {
          HttpExecuteRequest.Builder routed =
              HttpExecuteRequest.builder()
                  .request(
                      request
                          .httpRequest()
                          .toBuilder()
                          .appendRawQueryParameter("Action", "AssumeRoleWithWebIdentity")
                          .build());
          request.contentStreamProvider().ifPresent(routed::contentStreamProvider);
          request.metricCollector().ifPresent(routed::metricCollector);
          return delegate.prepareRequest(routed.build());
        }

        @Override
        public void close() {
          delegate.close();
        }
      };
    }
  }

  @Override
  public void close() {
    stsClient.close();
  }
}

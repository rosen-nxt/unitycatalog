package io.unitycatalog.server.service.credential.azure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.azure.core.util.Context;
import com.azure.identity.ClientSecretCredential;
import com.azure.identity.ClientSecretCredentialBuilder;
import com.azure.identity.WorkloadIdentityCredential;
import com.azure.identity.WorkloadIdentityCredentialBuilder;
import com.azure.storage.file.datalake.DataLakeServiceAsyncClient;
import com.azure.storage.file.datalake.DataLakeServiceClientBuilder;
import com.azure.storage.file.datalake.implementation.util.DataLakeSasImplUtil;
import com.azure.storage.file.datalake.models.UserDelegationKey;
import com.azure.storage.file.datalake.sas.DataLakeServiceSasSignatureValues;
import com.azure.storage.file.datalake.sas.PathSasPermission;
import io.unitycatalog.server.exception.BaseException;
import io.unitycatalog.server.exception.ErrorCode;
import io.unitycatalog.server.service.credential.CredentialContext;
import io.unitycatalog.server.service.credential.azure.AzureCredentialGenerator.DatalakeCredentialGenerator;
import io.unitycatalog.server.utils.NormalizedURL;
import io.unitycatalog.server.utils.ServerProperties;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import reactor.core.publisher.Mono;

class AzureWorkloadIdentityTest {
  private final Map<String, String> environment =
      Map.of(
          "AZURE_TENANT_ID",
          "tenant",
          "AZURE_CLIENT_ID",
          "client",
          "AZURE_FEDERATED_TOKEN_FILE",
          "/projected/token");

  @Test
  void selectsOnlyWorkloadIdentityAndPreservesScopedSas() {
    WorkloadIdentityCredential identity = mock(WorkloadIdentityCredential.class);
    DataLakeServiceAsyncClient client = mock(DataLakeServiceAsyncClient.class);
    when(client.getUserDelegationKey(any(), any()))
        .thenAnswer(
            invocation -> Mono.just(key(invocation.getArgument(0), invocation.getArgument(1))));
    try (var workload = workloadBuilder(identity);
        var legacy = mockConstruction(ClientSecretCredentialBuilder.class);
        var services = serviceBuilder(client)) {
      var generator = new DatalakeCredentialGenerator(workloadConfig(), environment);
      for (Set<CredentialContext.Privilege> privileges :
          java.util.List.of(
              CredentialContext.READ_ONLY,
              CredentialContext.READ_WRITE,
              Set.of(CredentialContext.Privilege.UPDATE))) {
        long before = System.currentTimeMillis();
        var credential = generator.generate(context("workload", "nested/table/", privileges));
        long after = System.currentTimeMillis();
        Map<String, String> sas = sasParameters(credential.getSasToken());
        OffsetDateTime signedStart = OffsetDateTime.parse(sas.get("st"));
        OffsetDateTime signedExpiry = OffsetDateTime.parse(sas.get("se"));
        var signatureValues =
            new DataLakeServiceSasSignatureValues(
                    signedExpiry, PathSasPermission.parse(sas.get("sp")))
                .setStartTime(signedStart);
        String expected =
            new DataLakeSasImplUtil(signatureValues, "container", "nested/table", true)
                .generateUserDelegationSas(
                    key(signedStart, signedExpiry), "workload", Context.NONE);
        assertThat(sas.get("sig")).isEqualTo(sasParameters(expected).get("sig"));
        assertThat(sas.get("sr")).isEqualTo("d");
        assertThat(sas.get("sdd")).isEqualTo("2");
        assertThat(sas.get("sp"))
            .isEqualTo(
                privileges.equals(CredentialContext.READ_ONLY)
                    ? "rl"
                    : privileges.equals(CredentialContext.READ_WRITE) ? "rwdl" : "wd");
        assertThat(credential.getExpirationTimeInEpochMillis())
            .isBetween(before + 3600000, after + 3600000);
        assertThat(OffsetDateTime.parse(sas.get("se")).toInstant().toEpochMilli())
            .isBetween(
                credential.getExpirationTimeInEpochMillis() - 999,
                credential.getExpirationTimeInEpochMillis());
        verify(services.constructed().get(services.constructed().size() - 1)).credential(identity);
        verify(services.constructed().get(services.constructed().size() - 1))
            .endpoint("https://workload.dfs.core.windows.net");
      }
      assertThat(workload.constructed()).hasSize(1);
      verify(workload.constructed().get(0)).tenantId("tenant");
      verify(workload.constructed().get(0)).clientId("client");
      verify(workload.constructed().get(0)).tokenFilePath("/projected/token");
      assertThat(legacy.constructed()).isEmpty();
      var start = ArgumentCaptor.forClass(OffsetDateTime.class);
      var expiry = ArgumentCaptor.forClass(OffsetDateTime.class);
      verify(client, org.mockito.Mockito.times(3))
          .getUserDelegationKey(start.capture(), expiry.capture());
      for (int i = 0; i < 3; i++) {
        assertThat(expiry.getAllValues().get(i))
            .isEqualTo(start.getAllValues().get(i).plusHours(1));
      }
    }
  }

  @Test
  void missingOrBlankInjectedIdentityFailsWithoutFallback() {
    try (var workload = mockConstruction(WorkloadIdentityCredentialBuilder.class);
        var legacy = mockConstruction(ClientSecretCredentialBuilder.class)) {
      for (String key : environment.keySet()) {
        Map<String, String> missing = new HashMap<>(environment);
        missing.remove(key);
        for (Map<String, String> invalid : java.util.List.of(missing, withBlank(key))) {
          assertThatThrownBy(() -> new DatalakeCredentialGenerator(workloadConfig(), invalid))
              .isInstanceOf(BaseException.class)
              .hasMessageContaining(key)
              .extracting(e -> ((BaseException) e).getErrorCode())
              .isEqualTo(ErrorCode.FAILED_PRECONDITION);
        }
      }
      assertThat(workload.constructed()).isEmpty();
      assertThat(legacy.constructed()).isEmpty();
    }
  }

  @Test
  void failedIdentityExchangeOrDelegationRequestDoesNotFallback() {
    DataLakeServiceAsyncClient client = mock(DataLakeServiceAsyncClient.class);
    when(client.getUserDelegationKey(any(), any()))
        .thenReturn(Mono.error(new IllegalStateException("sensitive-value")));
    try (var workload = workloadBuilder(mock(WorkloadIdentityCredential.class));
        var legacy = mockConstruction(ClientSecretCredentialBuilder.class);
        var services = serviceBuilder(client)) {
      var generator = new DatalakeCredentialGenerator(workloadConfig(), environment);
      assertThatThrownBy(
              () -> generator.generate(context("workload", "table", CredentialContext.READ_ONLY)))
          .isInstanceOf(BaseException.class)
          .hasMessage("Azure workload identity could not obtain a user-delegation key.")
          .hasMessageNotContaining("sensitive-value")
          .hasCauseInstanceOf(CompletionException.class)
          .extracting(e -> ((BaseException) e).getErrorCode())
          .isEqualTo(ErrorCode.FAILED_PRECONDITION);
      assertThat(workload.constructed()).hasSize(1);
      assertThat(legacy.constructed()).isEmpty();
    }
  }

  @Test
  void legacyModeStillSelectsClientSecret() {
    ClientSecretCredential identity = mock(ClientSecretCredential.class);
    try (var legacy =
            mockConstruction(
                ClientSecretCredentialBuilder.class,
                org.mockito.Mockito.withSettings().defaultAnswer(RETURNS_SELF),
                (builder, ctx) -> when(builder.build()).thenReturn(identity));
        var workload = mockConstruction(WorkloadIdentityCredentialBuilder.class)) {
      new DatalakeCredentialGenerator(
          ADLSStorageConfig.builder()
              .tenantId("tenant")
              .clientId("client")
              .clientSecret("test-secret")
              .build(),
          Map.of());
      verify(legacy.constructed().get(0)).tenantId("tenant");
      verify(legacy.constructed().get(0)).clientId("client");
      verify(legacy.constructed().get(0)).clientSecret("test-secret");
      assertThat(workload.constructed()).isEmpty();
    }
  }

  @Test
  void accountAllowlistIsEnforcedBeforeIdentityConstruction() {
    Properties props = new Properties();
    props.setProperty("adls.storageAccountName.0", "workload");
    props.setProperty("adls.authMode.0", "workload-identity");
    var vendor = new AzureCredentialVendor(new ServerProperties(props));
    try (var generators =
        mockConstruction(
            DatalakeCredentialGenerator.class,
            (generator, ctx) ->
                when(generator.generate(any()))
                    .thenReturn(AzureCredential.builder().sasToken("synthetic").build()))) {
      assertThatThrownBy(
              () ->
                  vendor.vendAzureCredential(
                      context("unconfigured", "table", CredentialContext.READ_ONLY)))
          .isInstanceOf(BaseException.class)
          .hasMessageContaining("configuration not found")
          .extracting(e -> ((BaseException) e).getErrorCode())
          .isEqualTo(ErrorCode.FAILED_PRECONDITION);
      assertThat(generators.constructed()).isEmpty();
      for (String host :
          java.util.List.of(
              "workload.attacker.example",
              "workload.dfs.core.windows.net.attacker.example",
              "workload.blob.core.windows.net",
              "workload")) {
        var forged =
            CredentialContext.create(
                NormalizedURL.from("abfss://container@" + host + "/table"),
                CredentialContext.READ_ONLY,
                Optional.empty());
        assertThatThrownBy(() -> vendor.vendAzureCredential(forged))
            .isInstanceOf(BaseException.class)
            .hasMessageContaining("configured public Azure DFS endpoint")
            .extracting(e -> ((BaseException) e).getErrorCode())
            .isEqualTo(ErrorCode.FAILED_PRECONDITION);
        assertThat(generators.constructed()).isEmpty();
      }
      assertThat(
              vendor
                  .vendAzureCredential(context("workload", "table", CredentialContext.READ_ONLY))
                  .getSasToken())
          .isEqualTo("synthetic");
      assertThat(generators.constructed()).hasSize(1);
    }
  }

  private ADLSStorageConfig workloadConfig() {
    return ADLSStorageConfig.builder()
        .storageAccountName("workload")
        .authMode(ADLSStorageConfig.AuthMode.WORKLOAD_IDENTITY)
        .build();
  }

  private Map<String, String> withBlank(String key) {
    Map<String, String> result = new HashMap<>(environment);
    result.put(key, " ");
    return result;
  }

  private CredentialContext context(
      String account, String path, Set<CredentialContext.Privilege> privileges) {
    return CredentialContext.create(
        NormalizedURL.from("abfss://container@" + account + ".dfs.core.windows.net/" + path),
        privileges,
        Optional.empty());
  }

  private MockedConstruction<WorkloadIdentityCredentialBuilder> workloadBuilder(
      WorkloadIdentityCredential identity) {
    return mockConstruction(
        WorkloadIdentityCredentialBuilder.class,
        org.mockito.Mockito.withSettings().defaultAnswer(RETURNS_SELF),
        (builder, ctx) -> when(builder.build()).thenReturn(identity));
  }

  private MockedConstruction<DataLakeServiceClientBuilder> serviceBuilder(
      DataLakeServiceAsyncClient client) {
    return mockConstruction(
        DataLakeServiceClientBuilder.class,
        org.mockito.Mockito.withSettings().defaultAnswer(RETURNS_SELF),
        (builder, ctx) -> when(builder.buildAsyncClient()).thenReturn(client));
  }

  private UserDelegationKey key(OffsetDateTime start, OffsetDateTime expiry) {
    return new UserDelegationKey()
        .setSignedObjectId("object")
        .setSignedTenantId("tenant")
        .setSignedStart(start)
        .setSignedExpiry(expiry)
        .setSignedService("b")
        .setSignedVersion("2020-02-10")
        .setValue("dGVzdC1vbmx5LXNpZ25pbmcta2V5");
  }

  private Map<String, String> sasParameters(String token) {
    Map<String, String> result = new HashMap<>();
    for (String part : token.split("&")) {
      String[] pair = part.split("=", 2);
      result.put(pair[0], URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
    }
    return result;
  }
}

package io.unitycatalog.server.service.credential.azure;

import com.azure.core.credential.TokenCredential;
import com.azure.core.http.HttpClient;
import com.azure.core.util.Context;
import com.azure.identity.ClientSecretCredentialBuilder;
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
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;

public interface AzureCredentialGenerator {
  AzureCredential generate(CredentialContext ctx);

  class StaticAzureCredentialGenerator implements AzureCredentialGenerator {
    private final ADLSStorageConfig config;

    public StaticAzureCredentialGenerator(ADLSStorageConfig config) {
      this.config = config;
    }

    @Override
    public AzureCredential generate(CredentialContext ctx) {
      String sasToken =
          String.format(
              "%s/%s/%s", config.getTenantId(), config.getClientId(), config.getClientSecret());
      return AzureCredential.builder()
          .sasToken(sasToken)
          .expirationTimeInEpochMillis(253370790000000L)
          .build();
    }
  }

  class DatalakeCredentialGenerator implements AzureCredentialGenerator {
    private final TokenCredential tokenCredential;
    private final ADLSStorageConfig.AuthMode authMode;

    public DatalakeCredentialGenerator(ADLSStorageConfig config) {
      this(config, System.getenv());
    }

    DatalakeCredentialGenerator(ADLSStorageConfig config, Map<String, String> environment) {
      this.authMode = config.getAuthMode();
      if (authMode == ADLSStorageConfig.AuthMode.WORKLOAD_IDENTITY) {
        config.validate();
        for (String key :
            Set.of("AZURE_TENANT_ID", "AZURE_CLIENT_ID", "AZURE_FEDERATED_TOKEN_FILE")) {
          if (environment.get(key) == null || environment.get(key).isBlank()) {
            throw new BaseException(
                ErrorCode.FAILED_PRECONDITION,
                "Azure workload identity requires injected " + key + ".");
          }
        }
        this.tokenCredential =
            new WorkloadIdentityCredentialBuilder()
                .tenantId(environment.get("AZURE_TENANT_ID"))
                .clientId(environment.get("AZURE_CLIENT_ID"))
                .tokenFilePath(environment.get("AZURE_FEDERATED_TOKEN_FILE"))
                .build();
      } else {
        this.tokenCredential =
            new ClientSecretCredentialBuilder()
                .tenantId(config.getTenantId())
                .clientId(config.getClientId())
                .clientSecret(config.getClientSecret())
                .build();
      }
    }

    @Override
    public AzureCredential generate(CredentialContext ctx) {
      ADLSLocationUtils.ADLSLocationParts locationParts =
          ADLSLocationUtils.parseLocation(ctx.getStorageBase());

      DataLakeServiceAsyncClient serviceClient =
          new DataLakeServiceClientBuilder()
              .httpClient(HttpClient.createDefault())
              .endpoint("https://" + locationParts.account())
              .credential(tokenCredential)
              .buildAsyncClient();

      // TODO: possibly make this configurable - defaulted to 1 hour right now
      OffsetDateTime start = OffsetDateTime.now();
      OffsetDateTime expiry = start.plusHours(1);
      UserDelegationKey key;
      try {
        key = serviceClient.getUserDelegationKey(start, expiry).toFuture().join();
      } catch (CompletionException e) {
        if (authMode == ADLSStorageConfig.AuthMode.WORKLOAD_IDENTITY) {
          throw new BaseException(
              ErrorCode.FAILED_PRECONDITION,
              "Azure workload identity could not obtain a user-delegation key.",
              e);
        }
        throw e;
      }

      PathSasPermission perms = resolvePrivileges(ctx.getPrivileges());
      DataLakeServiceSasSignatureValues sasSignatureValues =
          new DataLakeServiceSasSignatureValues(expiry, perms).setStartTime(start);

      // azure supports only downscoping to a single location for now
      // azure wants only the path
      String path = ctx.getLocations().get(0).toUri().getPath();
      // remove any preceding forward slashes or trailing forward slashes
      // hadoop ABFS strips trailing slash when preforming some operations so we need to vend
      // a cred for path without trailing slash
      path = path.replaceAll("^/+|/*$", "");

      String sasToken =
          new DataLakeSasImplUtil(sasSignatureValues, locationParts.container(), path, true)
              .generateUserDelegationSas(key, locationParts.accountName(), Context.NONE);

      return AzureCredential.builder()
          .sasToken(sasToken)
          .expirationTimeInEpochMillis(expiry.toInstant().toEpochMilli())
          .build();
    }

    private PathSasPermission resolvePrivileges(Set<CredentialContext.Privilege> privileges) {
      PathSasPermission result = new PathSasPermission();
      if (privileges.contains(CredentialContext.Privilege.UPDATE)) {
        result.setWritePermission(true);
        result.setDeletePermission(true);
      }
      if (privileges.contains(CredentialContext.Privilege.SELECT)) {
        result.setReadPermission(true);
        result.setListPermission(true);
      }
      return result;
    }
  }
}

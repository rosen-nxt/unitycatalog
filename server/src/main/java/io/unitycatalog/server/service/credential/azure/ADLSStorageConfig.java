package io.unitycatalog.server.service.credential.azure;

import io.unitycatalog.server.exception.BaseException;
import io.unitycatalog.server.exception.ErrorCode;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ADLSStorageConfig {
  public enum AuthMode {
    CLIENT_SECRET,
    WORKLOAD_IDENTITY;

    public static AuthMode fromConfig(String value) {
      if (value == null || value.equals("client-secret")) {
        return CLIENT_SECRET;
      }
      if (value.equals("workload-identity")) {
        return WORKLOAD_IDENTITY;
      }
      throw new BaseException(ErrorCode.INVALID_ARGUMENT, "Invalid ADLS authentication mode.");
    }
  }

  @Builder.Default private AuthMode authMode = AuthMode.CLIENT_SECRET;
  private String storageAccountName;
  private String tenantId;
  private String clientId;
  private String clientSecret;
  private String credentialGenerator;
  private boolean testMode;

  /** Validate the account allowlist and reject ambiguous authentication settings. */
  public void validate() {
    if (storageAccountName == null || storageAccountName.isBlank()) {
      throw new BaseException(ErrorCode.INVALID_ARGUMENT, "Invalid ADLS storage account name.");
    }
    if (authMode == AuthMode.WORKLOAD_IDENTITY) {
      if (tenantId != null
          || clientId != null
          || clientSecret != null
          || testMode
          || credentialGenerator != null) {
        throw new BaseException(
            ErrorCode.INVALID_ARGUMENT,
            "ADLS workload-identity mode cannot use client credentials, test mode or a custom"
                + " generator.");
      }
    } else if (authMode != AuthMode.CLIENT_SECRET
        || tenantId == null
        || tenantId.isBlank()
        || clientId == null
        || clientId.isBlank()
        || clientSecret == null
        || clientSecret.isBlank()) {
      throw new BaseException(
          ErrorCode.INVALID_ARGUMENT,
          "ADLS client-secret mode requires tenantId, clientId and clientSecret.");
    }
  }
}

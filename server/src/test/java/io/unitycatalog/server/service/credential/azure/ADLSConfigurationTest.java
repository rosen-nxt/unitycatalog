package io.unitycatalog.server.service.credential.azure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.unitycatalog.server.exception.BaseException;
import io.unitycatalog.server.exception.ErrorCode;
import io.unitycatalog.server.utils.ServerProperties;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class ADLSConfigurationTest {
  @Test
  void parsesMixedAccountsWithoutFakeSecrets() {
    Properties props = workloadIdentity();
    props.setProperty("adls.storageAccountName.1", "legacy");
    props.setProperty("adls.tenantId.1", "tenant");
    props.setProperty("adls.clientId.1", "client");
    props.setProperty("adls.clientSecret.1", "test-secret");
    var configs = new ServerProperties(props).getAdlsConfigurations();
    assertThat(configs).containsOnlyKeys("workload", "legacy");
    assertThat(configs.get("workload").getAuthMode())
        .isEqualTo(ADLSStorageConfig.AuthMode.WORKLOAD_IDENTITY);
    assertThat(configs.get("workload").getClientSecret()).isNull();
    assertThat(configs.get("legacy").getAuthMode())
        .isEqualTo(ADLSStorageConfig.AuthMode.CLIENT_SECRET);
    assertThat(configs.get("legacy").getClientSecret()).isEqualTo("test-secret");
    props.setProperty("adls.authMode.1", "client-secret");
    assertThat(new ServerProperties(props).getAdlsConfigurations()).hasSize(2);
    assertThat(new ServerProperties(new Properties()).getAdlsConfigurations()).isEmpty();
  }

  @Test
  void rejectsAmbiguousWorkloadIdentityWithoutLeakingValues() {
    for (String key :
        new String[] {"tenantId", "clientId", "clientSecret", "credentialGenerator", "testMode"}) {
      Properties props = workloadIdentity();
      props.setProperty("adls." + key + ".0", key.equals("testMode") ? "true" : "sensitive-value");
      assertInvalid(props, "cannot use");
    }
    Properties props = workloadIdentity();
    props.setProperty("adls.testMode.0", "false");
    assertThat(new ServerProperties(props).getAdlsConfigurations()).hasSize(1);
  }

  @Test
  void rejectsInvalidIncompleteDuplicateAndGappedConfiguration() {
    for (String mode : new String[] {"", "default", "managed-identity", "WORKLOAD_IDENTITY"}) {
      Properties props = workloadIdentity();
      props.setProperty("adls.authMode.0", mode);
      assertInvalid(props, "authentication mode");
    }
    for (String key : new String[] {"storageAccountName", "tenantId", "clientId", "clientSecret"}) {
      Properties props = new Properties();
      props.setProperty("adls.storageAccountName.0", "legacy");
      props.setProperty("adls.tenantId.0", "tenant");
      props.setProperty("adls.clientId.0", "client");
      props.setProperty("adls.clientSecret.0", "test-secret");
      props.remove("adls." + key + ".0");
      assertInvalid(props, key.equals("storageAccountName") ? "account name" : "requires");
      props.setProperty("adls." + key + ".0", " ");
      assertInvalid(props, key.equals("storageAccountName") ? "account name" : "requires");
    }
    Properties props = workloadIdentity();
    props.setProperty("adls.storageAccountName.1", "workload");
    props.setProperty("adls.authMode.1", "workload-identity");
    assertInvalid(props, "Duplicate");
    props.remove("adls.storageAccountName.1");
    assertInvalid(props, "account name");
    props.remove("adls.authMode.1");
    props.setProperty("adls.storageAccountName.2", "later");
    assertInvalid(props, "contiguous");
    props = workloadIdentity();
    props.setProperty("adls.testMode.0", "invalid");
    assertInvalid(props, "testMode");
    props = workloadIdentity();
    props.setProperty("adls.storageAccountName.9999999999999999999", "later");
    assertInvalid(props, "index");
  }

  @Test
  void preservesCompleteLegacyTestAndCustomGeneratorSettings() {
    Properties props = new Properties();
    props.setProperty("adls.storageAccountName.0", "test-bucket0");
    props.setProperty("adls.tenantId.0", "tenant");
    props.setProperty("adls.clientId.0", "client");
    props.setProperty("adls.clientSecret.0", "test-secret");
    props.setProperty("adls.testMode.0", "TRUE");
    props.setProperty("adls.credentialGenerator.0", "test.Generator");
    var config = new ServerProperties(props).getAdlsConfigurations().get("test-bucket0");
    assertThat(config.isTestMode()).isTrue();
    assertThat(config.getCredentialGenerator()).isEqualTo("test.Generator");
  }

  private Properties workloadIdentity() {
    Properties props = new Properties();
    props.setProperty("adls.storageAccountName.0", "workload");
    props.setProperty("adls.authMode.0", "workload-identity");
    return props;
  }

  private void assertInvalid(Properties props, String message) {
    assertThatThrownBy(() -> new ServerProperties(props).getAdlsConfigurations())
        .isInstanceOf(BaseException.class)
        .hasMessageContaining(message)
        .hasMessageNotContaining("sensitive-value")
        .extracting(e -> ((BaseException) e).getErrorCode())
        .isEqualTo(ErrorCode.INVALID_ARGUMENT);
  }
}

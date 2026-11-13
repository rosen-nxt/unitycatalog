import { expect, test } from "bun:test";
import { resolve } from "node:path";

const chart = resolve(import.meta.dir, "..");
function render(values: Record<string, unknown> = {}) {
  const result = Bun.spawnSync(
    [
      "helm",
      "template",
      "test",
      chart,
      "--set",
      "server.jwtKeypairSecret.create=false",
      "-f",
      "-",
    ],
    { stdin: Buffer.from(JSON.stringify(values)) },
  );
  expect(result.exitCode).toBe(0);
  return result.stdout
    .toString()
    .split(/^---\s*$/m)
    .filter((part) => part.trim())
    .map((part) => Bun.YAML.parse(part) as any);
}
function ui(documents: any[]) {
  return documents.find(
    (doc) =>
      doc.kind === "Deployment" &&
      doc.spec.template.spec.containers[0].name === "ui",
  ).spec.template.spec.containers[0];
}

test("default and explicitly disabled auth use bun and disable all browser providers", () => {
  for (const values of [{}, { auth: { enabled: false, provider: "oidc" } }]) {
    const container = ui(render(values));
    expect(container.command.join("\n")).toContain("bun run start");
    expect(container.command.join("\n")).not.toContain("yarn");
    for (const provider of ["GOOGLE", "OKTA", "KEYCLOAK", "OIDC"]) {
      expect(
        container.env.find(
          (entry: any) => entry.name === `REACT_APP_${provider}_AUTH_ENABLED`,
        ).value,
      ).toBe("false");
    }
  }
});

test("OIDC values are quoted, client ID uses a secret reference, allowlists are preserved", () => {
  const issuer = "https://login.microsoftonline.com/tenant/v2.0";
  const values = {
    auth: {
      enabled: true,
      provider: "oidc",
      clientSecretName: "oidc-client",
      oidcIssuer: issuer,
      oidcRedirectUri: "http://localhost:3000/login/callback",
      oidcScopes: "openid profile email",
      oidcProviderName: 'SSO "quoted" name',
      allowedIssuers: issuer,
      audiences: "spa-client-id",
    },
  };
  const documents = render(values);
  const container = ui(documents);
  const env = Object.fromEntries(
    container.env.map((entry: any) => [entry.name, entry]),
  );
  expect(env.REACT_APP_OIDC_AUTH_ENABLED.value).toBe("true");
  expect(env.REACT_APP_OIDC_ISSUER.value).toBe(issuer);
  expect(env.REACT_APP_OIDC_REDIRECT_URI.value).toBe(
    values.auth.oidcRedirectUri,
  );
  expect(env.REACT_APP_OIDC_SCOPES.value).toBe(values.auth.oidcScopes);
  expect(env.REACT_APP_OIDC_PROVIDER_NAME.value).toBe(
    values.auth.oidcProviderName,
  );
  expect(env.REACT_APP_OIDC_CLIENT_ID.valueFrom.secretKeyRef).toEqual({
    name: "oidc-client",
    key: "clientId",
  });
  expect(JSON.stringify(container)).not.toContain("clientSecret");
  const config = documents.find((doc) => doc.kind === "ConfigMap").data[
    "server.properties.template"
  ];
  expect(config).toContain(`server.allowed-issuers=${issuer}`);
  expect(config).toContain("server.audiences=spa-client-id");
});

test.each(["google", "okta", "keycloak"])(
  "existing provider %s retains its flag and client ID",
  (provider) => {
    const container = ui(
      render({
        auth: { enabled: true, provider, clientSecretName: "existing-client" },
      }),
    );
    expect(
      container.env.find(
        (entry: any) =>
          entry.name === `REACT_APP_${provider.toUpperCase()}_AUTH_ENABLED`,
      ).value,
    ).toBe("true");
    expect(
      container.env.find(
        (entry: any) => entry.name === "REACT_APP_OIDC_AUTH_ENABLED",
      ).value,
    ).toBe("false");
  },
);

test("disabled API proxy renders a valid package configuration", () => {
  const container = ui(render({ ui: { proxyApiRequests: false } }));
  expect(container.command.join("\n")).toContain("del(.proxy)");
});

function server(documents: any[]) {
  return documents.find(
    (doc) =>
      doc.kind === "Deployment" &&
      doc.spec.template.spec.containers[0].name === "server",
  );
}

test("server resources render independently of UI resources", () => {
  const uiResources = {
    requests: { cpu: "250m", memory: "1500Mi" },
    limits: { cpu: "1", memory: "3000Mi" },
  };
  for (const resources of [
    {
      requests: { cpu: "100m", memory: "256Mi" },
      limits: { cpu: "1", memory: "1024Mi" },
    },
    { requests: { cpu: "200m", memory: "512Mi" } },
    { limits: { cpu: "2", memory: "2Gi" } },
  ]) {
    const documents = render({
      server: { deployment: { resources } },
      ui: { deployment: { resources: uiResources } },
    });
    const podSpec = server(documents).spec.template.spec;
    expect(podSpec.containers[0].resources).toEqual(resources);
    expect(ui(documents).resources).toEqual(uiResources);
    expect(podSpec.initContainers[0]).not.toHaveProperty("resources");
  }
});

test("server resources remain omitted when unset or empty", () => {
  for (const values of [{}, { server: { deployment: { resources: {} } } }]) {
    const podSpec = server(render(values)).spec.template.spec;
    expect(podSpec.containers[0]).not.toHaveProperty("resources");
  }
});

test("workload identity permits explicit accounts without secret substitution", () => {
  const documents = render({
    storage: {
      credentials: {
        adls: [
          { storageAccountName: "workload", authMode: "workload-identity" },
          {
            storageAccountName: "secondaccount",
            authMode: "workload-identity",
          },
        ],
      },
    },
    serviceAccount: {
      create: true,
      annotations: {
        "azure.workload.identity/client-id": "identity-client-id",
      },
    },
    server: {
      deployment: { extraPodLabels: { "azure.workload.identity/use": "true" } },
    },
  });
  const config = documents.find((doc) => doc.kind === "ConfigMap").data[
    "server.properties.template"
  ];
  expect(config).toContain("adls.storageAccountName.0=workload");
  expect(config).toContain("adls.storageAccountName.1=secondaccount");
  expect(config).toContain("adls.authMode.0=workload-identity");
  expect(config).toContain("adls.authMode.1=workload-identity");
  for (const field of ["tenantId", "clientId", "clientSecret"]) {
    expect(config).not.toContain(`adls.${field}`);
  }
  expect(config).not.toContain("${ADLS_");
  expect(JSON.stringify(server(documents))).not.toContain("ADLS_");
  expect(
    server(documents).spec.template.metadata.labels[
      "azure.workload.identity/use"
    ],
  ).toBe("true");
  const account = documents.find((doc) => doc.kind === "ServiceAccount");
  expect(
    account.metadata.annotations["azure.workload.identity/client-id"],
  ).toBe("identity-client-id");
});

test.each([undefined, "client-secret"])(
  "legacy mode %s and mixed accounts retain secret references",
  (authMode) => {
    const legacy: Record<string, string> = {
      storageAccountName: "legacy",
      credentialsSecretName: "legacy-secret",
    };
    if (authMode !== undefined) legacy.authMode = authMode;
    const documents = render({
      storage: {
        credentials: {
          adls: [
            { storageAccountName: "workload", authMode: "workload-identity" },
            legacy,
          ],
        },
      },
    });
    const config = documents.find((doc) => doc.kind === "ConfigMap").data[
      "server.properties.template"
    ];
    expect(config).toContain("adls.authMode.0=workload-identity");
    expect(config).not.toContain("adls.clientSecret.0");
    expect(config).toContain("adls.clientSecret.1=${ADLS_CLIENT_SECRET_1}");
    const env = server(documents).spec.template.spec.initContainers[0].env;
    const adlsEnv = env.filter((entry: any) => entry.name.startsWith("ADLS_"));
    expect(adlsEnv).toHaveLength(3);
    for (const [name, key] of [
      ["TENANT_ID", "tenantId"],
      ["CLIENT_ID", "clientId"],
      ["CLIENT_SECRET", "clientSecret"],
    ]) {
      expect(
        adlsEnv.find((entry: any) => entry.name === `ADLS_${name}_1`).valueFrom
          .secretKeyRef,
      ).toEqual({ name: "legacy-secret", key });
    }
  },
);

test("invalid and ambiguous ADLS values fail chart rendering safely", () => {
  const workload = {
    storageAccountName: "workload",
    authMode: "workload-identity",
  };
  const cases: [Record<string, unknown>[], string][] = [
    [[{ authMode: "workload-identity" }], "storageAccountName is required"],
    [
      [{ ...workload, storageAccountName: " " }],
      "Invalid ADLS storageAccountName",
    ],
    [
      [{ ...workload, authMode: "default" }],
      "Invalid ADLS authentication mode",
    ],
    [[{ ...workload, authMode: "" }], "Invalid ADLS authentication mode"],
    [[{ storageAccountName: "legacy" }], "requires credentialsSecretName"],
    [[workload, workload], "Duplicate ADLS"],
  ];
  for (const key of [
    "credentialsSecretName",
    "tenantId",
    "clientId",
    "clientSecret",
    "credentialGenerator",
    "testMode",
  ]) {
    cases.push([
      [{ ...workload, [key]: key === "testMode" ? true : "sensitive-value" }],
      "cannot use",
    ]);
  }
  for (const [adls, message] of cases) {
    const result = Bun.spawnSync(
      [
        "helm",
        "template",
        "test",
        chart,
        "--set",
        "server.jwtKeypairSecret.create=false",
        "-f",
        "-",
      ],
      {
        stdin: Buffer.from(
          JSON.stringify({ storage: { credentials: { adls } } }),
        ),
      },
    );
    expect(result.exitCode).not.toBe(0);
    expect(result.stderr.toString()).toContain(message);
    expect(result.stderr.toString()).not.toContain("sensitive-value");
  }
});

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

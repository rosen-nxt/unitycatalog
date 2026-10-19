# Generic OIDC browser login (including Entra ID)

This fork adds authorization-code login with PKCE to the v0.7.0 UI. The browser
uses `oidc-client-ts`, exchanges the returned **ID token** at
`/api/1.0/unity-control/auth/tokens?ext=cookie`, and waits for `/scim2/Me` before
navigating to the catalog. The callback is `/login/callback` on the UI origin.

## Identity-provider setup

For Entra ID, configure a tenant-specific app registration:

1. Add a **Single-page application** redirect URI. For a local test use
   `http://localhost:3000/login/callback`; HTTPS localhost also works when that
   exact URI is registered and its certificate is trusted by the browser.
2. Use authorization code with PKCE. Do not enable implicit login or put a client
   secret in browser configuration.
3. Request `openid profile email`, and configure the optional ID-token `email`
   claim if needed. The test user must be permitted to sign in to the enterprise
   application and complete any required MFA/consent.
4. Use the tenant's exact v2 issuer and the SPA application/client ID as the UC
   audience. Do not use the enterprise application's object ID as the client ID.

Read-only Azure CLI discovery, when signed in:

```sh
az account show --query tenantId -o tsv
az ad sp list --display-name '<enterprise-app-name>' \
  --query '[].{name:displayName,clientId:appId}' -o json
az ad app show --id '<client-id>' \
  --query '{spaRedirectUris:spa.redirectUris,idTokenClaims:optionalClaims.idToken[].name}'
```

UC retains the release's identity policy. After issuer, signature/algorithm and
audience validation, an ID token resolves an **enabled local UC user** by `email`,
or by `sub` when the email claim is absent. A present-but-empty/null email fails
closed; `preferred_username`, `azp` and `client_id` are not human-user fallbacks.
An opaque Entra subject is not automatically mapped to an email address. Configure
claims/provisioning to match the real user; do not map login to the bootstrap
`admin` identity, which cannot be assumed through external token exchange.

## Isolated local server

Prerequisites: JDK 17 (`JAVA_HOME` set), the bundled `build/sbt`, Bun 1.3.14 (pinned
in `ui/package.json`), Node (the UI image uses 18.20.8), and `just`. Docker with
BuildKit/buildx is needed for the image checks; Helm 3 and Bun run chart checks.

From the repository root, create a disposable runtime directory rather than
using the checkout's database or signing keys. Keep this directory private.

```sh
export UC_TENANT_ID='<tenant-id>'
export UC_CLIENT_ID='<spa-client-id>'
export UC_LOCAL_DIR="$(mktemp -d "${TMPDIR:-/tmp}/uc-oidc.XXXXXX")"
mkdir -p "$UC_LOCAL_DIR/etc/conf"

cat > "$UC_LOCAL_DIR/etc/conf/server.properties" <<EOF
server.env=dev
server.authorization=enable
server.allowed-issuers=https://login.microsoftonline.com/${UC_TENANT_ID}/v2.0
server.audiences=${UC_CLIENT_ID}
server.include-stacktrace-in-error=false
server.cookie-timeout=PT1H
server.access-token-timeout=PT1H
storage-root.tables=file:${UC_LOCAL_DIR}/tables
storage-root.models=file:${UC_LOCAL_DIR}/models
EOF

cat > "$UC_LOCAL_DIR/etc/conf/hibernate.properties" <<'EOF'
hibernate.connection.driver_class=org.h2.Driver
hibernate.connection.url=jdbc:h2:file:./etc/db/oidc-local;DB_CLOSE_DELAY=-1
hibernate.hbm2ddl.auto=update
hibernate.show_sql=false
EOF

cp etc/conf/server.log4j2.properties "$UC_LOCAL_DIR/etc/conf/"
just server-package
just server-start "$UC_LOCAL_DIR"
```

The public server listens on port 8080; its internal Armeria listener uses 8081.
Stop it with Ctrl+C in that terminal. Startup generates signing material and a
bootstrap service token **inside the disposable directory**. The browser token
exchange does not need `server.client-secret`, `server.authorization-url` or
`server.token-url`; those settings serve separate confidential/CLI OAuth flows.

### Provision a non-admin test user

In another terminal, export the same `UC_LOCAL_DIR` and set `UC_TEST_EMAIL` to
the email expected in the actual ID token. The following creates a test catalog
and grants only `USE CATALOG`; it grants no metastore administration privilege.
Run once against the fresh local database. It reads the bootstrap token in
process without printing it or placing it on the command line.

```sh
export UC_TEST_EMAIL='<email-from-your-id-token>'
python3 - <<'PY'
import json, os, pathlib, urllib.request

token = (pathlib.Path(os.environ['UC_LOCAL_DIR']) / 'etc/conf/token.txt').read_text().strip()
email = os.environ['UC_TEST_EMAIL']
def request(method, path, body):
    req = urllib.request.Request('http://localhost:8080' + path,
        data=json.dumps(body).encode(), method=method,
        headers={'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json'})
    with urllib.request.urlopen(req) as response:
        return json.load(response)

request('POST', '/api/1.0/unity-control/scim2/Users', {
    'displayName': 'Local OIDC test user', 'active': True,
    'emails': [{'value': email, 'primary': True}]})
request('POST', '/api/2.1/unity-catalog/catalogs', {'name': 'oidc_local'})
request('PATCH', '/api/2.1/unity-catalog/permissions/catalog/oidc_local', {
    'changes': [{'principal': email, 'add': ['USE CATALOG']}]})
print('Local test user and catalog access provisioned.')
PY
```

## Start the UI

Copy `ui/.env.example` to **`ui/.env.local`**, enable OIDC, and replace the tenant
and client-ID placeholders. All `REACT_APP_*` values are public browser settings;
never add a browser client secret. Keep the other provider flags false when
testing OIDC alone. The redirect must match both the registration and current
UI origin. The UI reports incomplete/invalid OIDC settings rather than starting
a broken client.

```sh
just ui-install
BROWSER=none PORT=3000 just ui-start
```

Open `http://localhost:3000` and choose **Continue with Microsoft**. The host UI's
existing proxy targets `http://localhost:8080`, so UI and API share one browser
origin. Leave CRA's `HOST` unset when using the development proxy: its allowed-host
configuration is derived during startup. Do not disable host checking to repair
a startup/configuration error.

UC's cookie security settings are retained. Modern browsers treat localhost as
a special trustworthy context, but verify that the browser actually retains and
sends the Secure cookie. If it does not, register an HTTPS localhost callback and
set `HTTPS=true`, `SSL_CRT_FILE` and `SSL_KEY_FILE` to trusted certificate files in
`.env.local`. Non-localhost deployments need HTTPS and same-origin API routing.

CRA consumes `REACT_APP_*` settings when its development server starts or when
`bun run build` compiles a static bundle. Changing environment variables around
an already-built static bundle does not reconfigure it. Restart after changes.

## Manual acceptance check

Use an isolated local browser session. Record the artifact commit and boolean
conclusions, not raw tokens, browser-storage dumps, personal claims or screenshots
containing them.

- Login reaches the provider via code flow with PKCE, state and nonce, then returns
  to `/login/callback`. Navigation to the catalog occurs after UC exchange and
  current-user refresh; callback query parameters disappear.
- Verify the ID token's issuer/audience match the configured tenant/client, and
  that the supported email/sub claim matches the enabled local user. Inspect
  values only locally; record names/types and match/non-match conclusions.
- `/api/1.0/unity-control/scim2/Me` returns the expected non-admin user and
  `/api/2.1/unity-catalog/catalogs/oidc_local` succeeds using the UC cookie.
- Reload preserves the UC session. **Log out** clears UC access; a subsequent
  current-user request is unauthorized, and cached provider tokens do not sign
  the user back in. A fresh interactive login still works.
- A canceled callback, missing/unknown state or rejected UC exchange shows a
  generic failure with retry, without navigating as if login succeeded. Use local
  fixtures for invalid issuer/audience/signature and unknown/disabled-user cases;
  do not change tenant/production policy to test rejection.

Logout is UC-only plus local OIDC cleanup, not provider-wide single sign-out.
The next login requests an interactive provider flow (`prompt=login`).

## Reproducible automated checks and images

```sh
just format-java
just server-auth-test
just server-test
just ui-install
just ui-test
just ui-build
just ui-format-check
just chart-test
just ui-image
just ui-image-smoke
just server-image
just server-image-smoke
```

The UI smoke test uses Linux Docker host networking and temporary random ports,
executes the rendered chart command, checks the callback bundle/deep link and
API proxy, and removes its test containers. No cluster is needed.

Build the server with repository-root context (`Dockerfile`) and UI with `ui/`
context (`ui/Dockerfile`). The server starts `bin/start-uc-server`; the UI starts
`bun run start`. The UI chart updates the proxy before that command. The root
`.dockerignore` excludes generated signing keys and bootstrap tokens; image checks
verify absence before startup. Always build from the reviewed source with only
placeholder tracked configuration. Image publication is a separate release task.

## Deployment-consumer contract and limits

- Fork integration branch: `feat/rosen-0.7.0`; upstream baseline:
  `2b17f3b5a1d06c0adbf22f4c11ea888c01a36415`. Pin the reviewed final commit or an
  immutable release tag, not this moving branch or the chart's inherited `main`
  image default. Source OIDC changes came from `feat/oidc-ui`; final SHAs and actual
  verification/review results belong in the release handoff.
- Chart path: `helm/`. OIDC values and client-ID secret mapping are documented in
  `helm/README.md`. Server allowlists remain `server.allowed-issuers` and
  `server.audiences`. The existing server-init secret reference also expects a
  `clientSecret` key; an empty string is sufficient for browser-only PKCE.
- Persist signing keys across server restarts. The existing chart interface is
  `server.jwtKeypairSecret.name` with `create: false` for an externally managed
  Secret containing `private_key.der` or `private_key.pem`, plus `key_id.txt`.
  The init container derives the public key. The `create: true` path generates
  chart-managed material; downstream release must decide ownership/persistence.
- `server.config.extraProperties` appends server properties; explicit config
  override is also supported. Probe interfaces are
  `server.deployment.enable{Liveness,Readiness,Startup}Probe` and the corresponding
  `{liveness,readiness,startup}Probe` objects. Defaults are TCP checks; HTTP probe
  routing/ports and production operating settings belong to deployment.
- Browser Entra authentication does not establish Azure storage identity. The
  release ADLS credential vendor uses configured tenant/client/client-secret
  credentials to obtain a user-delegation SAS and supports downscoping to one
  location. It does not use `DefaultAzureCredential`/workload identity for this
  credential-vending path. A pod annotation alone does not add that capability.

## Cleanup

Stop the server/UI terminals or only the containers started for this local test.
Keep `UC_LOCAL_DIR` if you intend to resume: it contains the disposable DB, user
and signing material. When finished, delete **that exact disposable directory**
and the local `.env.local` you created. Do not remove the checkout's existing
database, other worktrees, or shared runtime configuration. Sign out of/close the
isolated test browser session as appropriate.

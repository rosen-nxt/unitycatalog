# Azure ADLS credential vending

Unity Catalog vends directory-scoped user-delegation SAS credentials for ADLS
tables, volumes and model artifacts. Server storage identity is separate from
browser OIDC login and UC resource authorization. Each permitted storage account
must be configured explicitly; an unconfigured account is denied even when the
pod has an Azure identity. Azure storage credentials/external locations are not
supported by this vendor.

## Server configuration

Use contiguous indices beginning at zero. Accounts must be unique. An omitted
`authMode` means `client-secret`, preserving existing complete configurations.
Missing or blank required values, unknown modes, duplicates and index gaps fail
configuration loading rather than silently skipping an account.

```properties
# Secretless, explicitly allowed account; no tenant/client/secret properties.
adls.storageAccountName.0=permittedworkloadaccount
adls.authMode.0=workload-identity
```

Workload-identity mode uses only Azure SDK `WorkloadIdentityCredential`, not
`DefaultAzureCredential`, Azure CLI credentials, node managed identity or a
client-secret fallback. The **server container** must receive nonblank
`AZURE_TENANT_ID`, `AZURE_CLIENT_ID` and `AZURE_FEDERATED_TOKEN_FILE`. Missing
injection rejects vending with `FAILED_PRECONDITION`. An unreadable/invalid
projected token, federation mismatch, failed Entra exchange or denied delegation
request also fails vending; none selects another credential type. All opted-in
accounts use the same pod identity. Do not set `tenantId`, `clientId`,
`clientSecret`, enabled `testMode` or `credentialGenerator` on those entries.

For a legacy account, retain its indexed `storageAccountName`, `tenantId`,
`clientId` and `clientSecret` settings, optionally adding `authMode=client-secret`.
Supply actual secrets through the approved secret store, never Git, task notes
or command output. Complete legacy test/custom-generator configurations remain
supported. Remove incomplete demonstration entries instead of relying on the old
parser's silent truncation behavior.

## Kubernetes and Helm

First provision the existing intended identity's federation through the approved
infrastructure process. The cluster needs its OIDC issuer and Azure workload
identity mutating webhook enabled. The federated credential must match the
cluster issuer, the exact `system:serviceaccount:<namespace>:<service-account>`
subject and audience `api://AzureADTokenExchange`.

This example uses chart-created service account `uc-storage`. Replace the client
ID placeholder with the intended managed identity's non-secret client ID. Merge
this storage snippet with the existing reviewed UC authentication/authorization
and deployment values; it does not configure caller authorization:

```yaml
storage:
  credentials:
    adls:
      - storageAccountName: permittedworkloadaccount
        authMode: workload-identity
serviceAccount:
  create: true
  name: uc-storage
  annotations:
    azure.workload.identity/client-id: "<managed-identity-client-id>"
server:
  deployment:
    extraPodLabels:
      azure.workload.identity/use: "true"
```

For an existing externally managed service account, use `create: false` and its
name; manage its annotation and matching federation externally. Confirm webhook
injection on the **server**, not merely the init container or service account.
The projected token file must be readable by the server UID, remain mounted and
rotate normally. Avoid webhook skip-container rules that exclude the server.
Do not copy the federated token into properties or a Kubernetes Secret.

The chart emits only account name and auth mode for workload identity; its init
container does not load an ADLS Secret for that entry. A mixed list may add a
legacy entry with `credentialsSecretName` referencing keys `tenantId`, `clientId`
and `clientSecret`. Those legacy secrets still enter init-container environment
and generated `server.properties`; restrict pod exec, configuration-volume and
debug access, and follow existing rotation/restart controls. Workload-identity
entries reject that secret reference. Explicit config overrides/extra properties
are operator-managed escape hatches: do not override generated account modes or
inject conflicting credential fields. Server validation remains authoritative.

## Permissions, scope and lifecycle

- Delegation-key issuance requires
  `Microsoft.Storage/storageAccounts/blobServices/generateUserDelegationKey/action`
  at **storage-account scope or above**. Prefer account-scoped **Storage Blob
  Delegator** plus independently scoped data permissions, not subscription-wide
  Owner. A container-scoped data role alone cannot request a delegation key.
- Give the issuer only required data access, for example container-scoped
  Storage Blob Data Reader/Contributor, or reviewed HNS path ACLs with traversal
  permissions on ancestors. Broad RBAC data permissions take precedence over
  ACL checks; an ACL cannot narrow an already broad data role. Do not blindly
  remove existing Owner assignments without inventorying dependent workloads and
  approving a safe migration.
- Effective SAS access is the intersection of the issuer's data RBAC/ACL and
  the signed path/permissions. UC authorizes the caller before vending; account
  allowlisting is not a replacement for UC privileges or Azure authorization.
- Existing behavior is preserved: one-hour delegation key and SAS, directory
  resource (`sr=d`) scoped to the requested container/path and descendants,
  normalized trailing slashes, SELECT → read/list (`rl`), UPDATE → write/delete
  (`wd`), combined → `rwdl`. No ownership, ACL-edit or move permission is added.
  Requests must target a specific directory, not a storage root. Clock skew can
  affect immediate validity because the start time is the server's current time.
- The SDK caches short-lived Entra access tokens and rereads the projected
  federated token on refresh. UC requests a delegation key for each generation;
  any existing UC credential cache may reuse SAS until its configured expiry
  policy. Test vending across projected-token and Entra-token refresh in the
  actual cluster. Changing configuration or rolling back does **not** invalidate
  already issued SAS; clients must refresh before expiry. Follow Azure's
  delegation-key revocation incident process when necessary.
- Use TLS for catalog and storage traffic. The inherited SAS generation does
  not add an HTTPS-only `spr` restriction; do not treat a SAS as transport-safe
  by itself. Never print tokens, SAS query strings, delegation keys, HTTP bodies
  containing credentials or secret-bearing generated properties. Disable HTTP
  wire logging and sanitize client errors/telemetry during verification.

See Microsoft's [user-delegation SAS authorization and RBAC reference](https://learn.microsoft.com/en-us/rest/api/storageservices/create-user-delegation-sas)
and [AKS workload identity prerequisites](https://learn.microsoft.com/en-us/azure/aks/workload-identity-overview).

## Release, migration and rollback

This feature requires a new reviewed fork server **and chart** release. Existing
deployed client-secret-only releases do not gain it from a pod annotation or new
values. Do not overwrite the current production release or retag existing image
or chart artifacts. Before rollout, choose a new release version through the
authorized release process, build from the accepted commit, publish immutable
paired image/chart artifacts with source provenance, and pin the reviewed image
digest (or immutable tag) and chart version/digest downstream. The inherited
chart's `main` image default and development `SNAPSHOT` are not release pins.

Inventory existing indexed accounts and remove incomplete entries/duplicates;
then run `just server-adls-test`, `build/sbt javafmtCheckAll`, `just chart-test`
and full server tests. Render intended values against the new chart before
activating them. Confirm federation, scoped RBAC/ACL and private networking before
the authorized GitOps update; leave development unchanged unless separately
approved. With public access disabled, both DFS and Blob private endpoints,
private DNS/VNet links and client connectivity must be independently verified:
ADLS operations can use or redirect between both endpoints. Successful SAS
issuance alone does not prove storage connectivity.

Roll back image/chart and values together to the prior known-good pinned bundle
through the same authorized process. Never leave workload-identity entries with
the old client-secret-only parser: it may silently ignore them. Do not invent a
client secret to make rollback work; restore the previous reviewed configuration
and report any previously absent vending capability honestly. Retain independent
networking resources and account for already-issued SAS validity.

## Verification boundaries

Focused tests mock Azure exchange/delegation and use synthetic signing keys to
verify credential selection, fail-closed configuration, directory signature,
expiry and permissions. Chart tests cover mixed modes and absence of workload
identity secret substitution. They are not evidence of working live federation,
effective Azure permissions or a published release.

After security/operational review and separately authorized deployment, verify
vending and model/table read/write operations from the actual in-cluster client
context, plus denial for an unauthorized UC principal, unconfigured account,
out-of-scope storage path, read-only write attempt and expired SAS. Keep tokens
only in memory and record outcomes, not credentials. Also run the agreed external
lakehouse-client smoke with direct storage access disabled: create a unique
`fpa_dev.test` table in the approved production `unity-catalog` container, write
two rows, read them back and retain the successful test table. On write failure,
remove only that newly created metadata, never existing metadata or storage data.
An external success is additional evidence, not a substitute for in-cluster and
negative-access checks. Record release provenance, review evidence and any unmet
runtime/networking gates in the task handoff.

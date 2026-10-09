# SeaweedFS S3 credential vending with Entra

Unity Catalog can vend SeaweedFS temporary S3 credentials after authorizing the
request through its normal table, volume, model or path APIs. Authentication to
Unity Catalog is separate from the server's storage-broker identity.

Configure the existing per-bucket storage entry in `server.properties`:

```properties
s3.bucketPath.0=s3://rofm-storage
s3.region.0=us-east-1
s3.awsRoleArn.0=arn:aws:iam::role/UnityCatalogSeaweedBrokerRole
s3.stsEndpoint.0=https://seaweed.example.com
s3.oidcScope.0=api://<seaweed-api-client-id>/.default
s3.sessionDurationSeconds.0=900
```

Setting `s3.stsEndpoint.N` selects the SeaweedFS OIDC generator for that bucket.
Do not configure static access/secret keys, session tokens, or a custom credential
generator for the same entry. Existing AWS entries without this setting retain
their current behavior. The endpoint must be an HTTPS origin without a path,
query, fragment or user information; loopback HTTP is allowed for local tests.

The server uses Azure `DefaultAzureCredential`. On AKS, configure the service
account's managed-identity client-ID annotation, the pod workload-identity label,
and a federated identity credential for that service account. Azure Identity
exchanges the projected service-account assertion for an Entra API access token.
It does not send the projected AKS JWT to SeaweedFS. Assign the broker application
role on the Seaweed API to this managed identity; Azure storage RBAC is unrelated.

SeaweedFS must validate the Entra token issuer and audience, and the broker role's
trust policy must restrict assumption to the intended identity and app role.
Use a dedicated broker role with only the allowed buckets/prefixes. Provider role
mapping alone is not an authorization gate for a caller-specified STS role ARN in
SeaweedFS 4.40; put claim restrictions in the trust policy.

Every credential request generates an inline session policy using the authorized
locations and READ/READ_WRITE privileges. The generator calls
`AssumeRoleWithWebIdentity` with this policy, a unique role-session name and the
configured duration (default 900 seconds, valid range 900–43200). Effective S3
access is the intersection of the broker role and this session policy. Credentials
include the actual STS expiration so clients can renew; no unscoped session is
cached and reused across users/paths. SeaweedFS may further cap the duration by its
configuration and the source token's remaining lifetime.

For an external location with a stored AWS-role credential, its role ARN must
equal the configured broker role. Other roles fail closed rather than silently
falling back to AWS STS. AWS `external_id` is not used by this web-identity flow:
the Entra issuer/audience/identity conditions replace that trust mechanism.

The HTTP transport keeps `Action=AssumeRoleWithWebIdentity` in the URL for Envoy
query-based routing, with the JWT and policy only in the form body. Requests are
unsigned, so the SeaweedFS endpoint must be accessible without browser login; STS
performs token validation. Do not enable SDK wire logging or log tokens/responses.

Data engines must use the SeaweedFS S3 endpoint, path-style access where required,
and a session-token-aware provider that refreshes through Unity Catalog. The
catalog returns AWS-compatible temporary credentials, not an S3 endpoint override.

Test end-to-end against the pinned SeaweedFS release: a READ session must reject
writes, any session must reject another table's prefix, and expired sessions must
fail. A rejected Entra/STS exchange must not fall back to static credentials or AWS.

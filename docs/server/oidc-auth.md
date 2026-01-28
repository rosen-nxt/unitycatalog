# Generic OIDC Authentication Configuration

The Unity Catalog UI supports generic OIDC (OpenID Connect) authentication, which allows to integrate with any OIDC-compliant identity provider, including:

Tested:
- Microsoft Entra ID (Azure AD)
- Auth0

## Configuration

To enable OIDC authentication, update your `ui/.env` file with the following configuration:

```bash
# Generic OIDC config
REACT_APP_OIDC_AUTH_ENABLED=true
REACT_APP_OIDC_ISSUER=https://your-identity-provider.com
REACT_APP_OIDC_CLIENT_ID=your-client-id
REACT_APP_OIDC_REDIRECT_URI=http://localhost:3000/login/callback
REACT_APP_OIDC_SCOPES=openid profile email
REACT_APP_OIDC_PROVIDER_NAME=SSO
```

### Configuration Parameters

- **REACT_APP_OIDC_AUTH_ENABLED**: Set to `true` to enable OIDC authentication
- **REACT_APP_OIDC_ISSUER**: The OIDC issuer URL (authority URL) of your identity provider
- **REACT_APP_OIDC_CLIENT_ID**: Your application's client ID
- **REACT_APP_OIDC_REDIRECT_URI**: The redirect URI after authentication (default: `<ui-origin>/login/callback`)
- **REACT_APP_OIDC_SCOPES**: Space-separated list of OAuth scopes (default: `openid profile email`)
- **REACT_APP_OIDC_PROVIDER_NAME**: Display name for the login button (default: `SSO`)

## Provider-Specific Examples

### Microsoft Entra ID (Azure AD)

```bash
REACT_APP_OIDC_AUTH_ENABLED=true
REACT_APP_OIDC_ISSUER=https://login.microsoftonline.com/{tenant-id}/v2.0
REACT_APP_OIDC_CLIENT_ID=your-application-client-id
REACT_APP_OIDC_REDIRECT_URI=http://localhost:3000/login/callback
REACT_APP_OIDC_SCOPES=openid profile email
REACT_APP_OIDC_PROVIDER_NAME=Microsoft
```

## Server Configuration

In addition to the UI configuration, you must also configure the Unity Catalog server to accept tokens from your OIDC provider. Edit `etc/conf/server.properties`:

```properties
server.authorization=enable
server.authorization-url=https://your-identity-provider.com/authorize
server.token-url=https://your-identity-provider.com/token
server.client-id=your-client-id
server.client-secret=your-client-secret
server.allowed-issuers=https://your-identity-provider.com,https://your-second-identity-provider.com
server.audiences=your-client-id,another-audience
```

For Entra ID:
```properties
server.authorization=enable
server.authorization-url=https://login.microsoftonline.com/{tenant-id}/oauth2/v2.0/authorize
server.token-url=https://login.microsoftonline.com/{tenant-id}/oauth2/v2.0/token
server.client-id=your-application-client-id
server.client-secret=your-client-secret
server.allowed-issuers=https://login.microsoftonline.com/{tenant-id}/v2.0
server.audiences=your-client-id
```

#### Multiple Identity Providers

You can configure multiple issuers and audiences by separating them with commas:

```properties
server.allowed-issuers=https://login.microsoftonline.com/{tenant-id}/v2.0,https://accounts.google.com
server.audiences=your-azure-client-id,your-google-client-id.apps.googleusercontent.com
```

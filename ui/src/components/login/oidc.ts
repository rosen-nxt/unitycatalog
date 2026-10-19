import { UserManager, WebStorageStateStore } from 'oidc-client-ts';

export const oidcEnabled = process.env.REACT_APP_OIDC_AUTH_ENABLED === 'true';
export const oidcProviderName =
  process.env.REACT_APP_OIDC_PROVIDER_NAME || 'OIDC';

let manager: UserManager | undefined;
let stateStore: WebStorageStateStore | undefined;
let callback: Promise<void> | undefined;

export function getOidcManager(): UserManager | undefined {
  if (!oidcEnabled) return;
  if (manager) return manager;

  const authority = process.env.REACT_APP_OIDC_ISSUER?.trim();
  const clientId = process.env.REACT_APP_OIDC_CLIENT_ID?.trim();
  const redirectUri =
    process.env.REACT_APP_OIDC_REDIRECT_URI ||
    `${window.location.origin}/login/callback`;
  const scope = process.env.REACT_APP_OIDC_SCOPES || 'openid profile email';
  if (!authority || !clientId || !scope.split(/\s+/).includes('openid')) return;
  try {
    const issuer = new URL(authority);
    const redirect = new URL(redirectUri);
    if (
      !['http:', 'https:'].includes(issuer.protocol) ||
      issuer.username ||
      issuer.password ||
      issuer.search ||
      issuer.hash ||
      redirect.origin !== window.location.origin ||
      redirect.pathname !== '/login/callback' ||
      redirect.search ||
      redirect.hash
    )
      return;
  } catch {
    return;
  }

  stateStore = new WebStorageStateStore({
    store: window.sessionStorage,
    prefix: 'uc.oidc.state.',
  });
  manager = new UserManager({
    authority,
    client_id: clientId,
    redirect_uri: redirectUri,
    response_type: 'code',
    response_mode: 'query',
    scope,
    disablePKCE: false,
    stateStore,
    userStore: new WebStorageStateStore({
      store: window.sessionStorage,
      prefix: 'uc.oidc.user.',
    }),
    automaticSilentRenew: false,
    loadUserInfo: false,
  });
  return manager;
}

async function clearOidcStorage(): Promise<void> {
  const client = getOidcManager();
  if (!client) return;
  await client.removeUser();
  const store = stateStore;
  if (store) {
    await Promise.all(
      (await store.getAllKeys()).map((key) => store.remove(key)),
    );
  }
}

export async function clearOidcSession(): Promise<void> {
  await clearOidcStorage();
  callback = undefined;
}

export async function startOidcSignin(): Promise<void> {
  const client = getOidcManager();
  if (!client) throw new Error('OIDC is not configured');
  await clearOidcSession();
  const nonce = Array.from(
    window.crypto.getRandomValues(new Uint8Array(32)),
    (byte) => byte.toString(16).padStart(2, '0'),
  ).join('');
  await client.signinRedirect({ nonce, prompt: 'login' });
}

// Share the whole exchange across StrictMode effects/remounts, not just the IdP callback.
export function completeOidcSignin(
  loginWithToken: (token: string) => Promise<void>,
): Promise<void> {
  if (!callback) {
    const url = window.location.href;
    window.history.replaceState(window.history.state, '', '/login/callback');
    callback = (async () => {
      const client = getOidcManager();
      if (!client) throw new Error('OIDC is not configured');
      try {
        const user = await client.signinRedirectCallback(url);
        if (!user.id_token) throw new Error('No ID token received');
        await loginWithToken(user.id_token);
      } finally {
        await clearOidcStorage();
      }
    })();
  }
  return callback;
}

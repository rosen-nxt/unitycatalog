import { UserManager } from 'oidc-client-ts';
import { waitFor } from '@testing-library/react';

const mockCallback = jest.fn();
const mockRedirect = jest.fn();
const mockRemoveUser = jest.fn();
jest.mock('oidc-client-ts', () => ({
  ...jest.requireActual('oidc-client-ts'),
  UserManager: jest.fn().mockImplementation(() => ({
    signinRedirectCallback: mockCallback,
    signinRedirect: mockRedirect,
    removeUser: mockRemoveUser,
  })),
}));

let oidc: typeof import('./oidc');
const originalEnv = process.env;

beforeEach(() => {
  process.env = {
    ...originalEnv,
    REACT_APP_OIDC_AUTH_ENABLED: 'true',
    REACT_APP_OIDC_ISSUER: 'https://issuer.example.com/tenant/v2.0',
    REACT_APP_OIDC_CLIENT_ID: 'spa-client',
    REACT_APP_OIDC_REDIRECT_URI: 'http://localhost/login/callback',
    REACT_APP_OIDC_SCOPES: 'openid profile email',
  };
  window.history.replaceState(
    {},
    '',
    '/login/callback?code=private-code&state=state',
  );
  sessionStorage.clear();
  jest.clearAllMocks();
  (UserManager as jest.Mock).mockImplementation(() => ({
    signinRedirectCallback: mockCallback,
    signinRedirect: mockRedirect,
    removeUser: mockRemoveUser,
  }));
  mockCallback.mockReset().mockResolvedValue({ id_token: 'private-id-token' });
  mockRedirect.mockReset().mockResolvedValue(undefined);
  mockRemoveUser.mockReset().mockResolvedValue(undefined);
  jest.isolateModules(() => {
    oidc = require('./oidc');
  });
});
afterEach(() => {
  process.env = originalEnv;
});

test.each([
  { REACT_APP_OIDC_AUTH_ENABLED: 'false' },
  { REACT_APP_OIDC_CLIENT_ID: '' },
  { REACT_APP_OIDC_ISSUER: 'not a URL' },
  { REACT_APP_OIDC_SCOPES: 'profile' },
  { REACT_APP_OIDC_REDIRECT_URI: 'https://other.example.com/login/callback' },
  { REACT_APP_OIDC_REDIRECT_URI: 'http://localhost/wrong' },
])(
  'does not construct a client with disabled/incomplete configuration: %s',
  (env) => {
    Object.assign(process.env, env);
    jest.isolateModules(() => {
      oidc = require('./oidc');
    });
    expect(oidc.getOidcManager()).toBeUndefined();
    expect(UserManager).not.toHaveBeenCalled();
  },
);

test('uses code/PKCE, fresh nonce and interactive login, with session storage only', async () => {
  await oidc.startOidcSignin();
  const settings = (UserManager as jest.Mock).mock.calls[0][0];
  expect(settings).toMatchObject({
    response_type: 'code',
    response_mode: 'query',
    disablePKCE: false,
    automaticSilentRenew: false,
    loadUserInfo: false,
  });
  expect(settings).not.toHaveProperty('client_secret');
  const firstNonce = mockRedirect.mock.calls[0][0].nonce;
  expect(firstNonce).toMatch(/^[a-f0-9]{64}$/);
  expect(mockRedirect).toHaveBeenCalledWith({
    nonce: firstNonce,
    prompt: 'login',
  });
  await settings.stateStore.set('pending', 'transient-state');
  expect(sessionStorage.getItem('uc.oidc.state.pending')).toBe(
    'transient-state',
  );
  await oidc.startOidcSignin();
  expect(mockRedirect.mock.calls[1][0].nonce).not.toBe(firstNonce);
  expect(sessionStorage.getItem('uc.oidc.state.pending')).toBeNull();
  expect(localStorage.length).toBe(0);
});

test('shares callback and awaits UC exchange, clears URL and transient material', async () => {
  const client = oidc.getOidcManager();
  expect(client).toBeDefined();
  const store = (UserManager as jest.Mock).mock.calls[0][0].stateStore;
  await store.set('pending', 'transient-state');
  sessionStorage.setItem('unrelated', 'preserved');
  let finish!: () => void;
  const login = jest.fn(
    () =>
      new Promise<void>((resolve) => {
        finish = resolve;
      }),
  );
  let settled = false;
  const first = oidc.completeOidcSignin(login);
  expect(oidc.completeOidcSignin(login)).toBe(first);
  first.then(
    () => {
      settled = true;
    },
    () => {},
  );
  await waitFor(() => expect(login).toHaveBeenCalledWith('private-id-token'));
  expect(mockCallback).toHaveBeenCalledTimes(1);
  expect(mockCallback).toHaveBeenCalledWith(
    'http://localhost/login/callback?code=private-code&state=state',
  );
  expect(login).toHaveBeenCalledWith('private-id-token');
  expect(settled).toBe(false);
  expect(window.location.search).toBe('');
  expect(mockRemoveUser).not.toHaveBeenCalled();
  finish();
  await first;
  expect(mockRemoveUser).toHaveBeenCalledTimes(1);
  expect(sessionStorage.getItem('uc.oidc.state.pending')).toBeNull();
  expect(sessionStorage.getItem('unrelated')).toBe('preserved');
});

test.each([
  'missing state',
  'unknown state',
  'nonce mismatch',
  'access_denied',
])('propagates library rejection: %s', async (message) => {
  mockCallback.mockRejectedValue(new Error(message));
  const login = jest.fn();
  await expect(oidc.completeOidcSignin(login)).rejects.toThrow(message);
  expect(login).not.toHaveBeenCalled();
  expect(mockRemoveUser).toHaveBeenCalled();
  expect(window.location.search).toBe('');
});

test('rejects missing ID token and allows a fresh retry after failure', async () => {
  mockCallback.mockResolvedValue({});
  const login = jest.fn().mockResolvedValue(undefined);
  await expect(oidc.completeOidcSignin(login)).rejects.toThrow('No ID token');
  expect(login).not.toHaveBeenCalled();
  await oidc.startOidcSignin();
  mockCallback.mockResolvedValue({ id_token: 'fresh-id-token' });
  await oidc.completeOidcSignin(login);
  expect(login).toHaveBeenCalledWith('fresh-id-token');
});

test('exchange failure rejects and logout clears cached IdP state without redirecting', async () => {
  const login = jest.fn().mockRejectedValue(new Error('UC denied'));
  await expect(oidc.completeOidcSignin(login)).rejects.toThrow('UC denied');
  await oidc.clearOidcSession();
  expect(mockRemoveUser).toHaveBeenCalledTimes(2);
  expect(mockRedirect).not.toHaveBeenCalled();
});

test.each([
  'http://localhost/login/callback?code=code',
  'http://localhost/login/callback?code=code&state=unknown',
])('real library rejects missing or unknown stored state: %s', async (url) => {
  const { UserManager: RealUserManager } = jest.requireActual('oidc-client-ts');
  const client = new RealUserManager({
    authority: 'https://issuer.example.com',
    client_id: 'spa',
    redirect_uri: 'http://localhost/login/callback',
  });
  await expect(client.signinRedirectCallback(url)).rejects.toThrow(/state/i);
});

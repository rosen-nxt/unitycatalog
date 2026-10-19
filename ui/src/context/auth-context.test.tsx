import { act, render, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AuthProvider, useAuth } from './auth-context';
import { CLIENT } from './client';
import { clearOidcSession } from '../components/login/oidc';

const mockNotification = jest.fn();
jest.mock('../utils/NotificationContext', () => ({
  useNotification: () => ({ setNotification: mockNotification }),
}));
jest.mock('../components/login/oidc', () => ({
  clearOidcSession: jest.fn().mockResolvedValue(undefined),
}));
jest.mock('./client', () => ({ CLIENT: { request: jest.fn() } }));

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: Error) => void;
  const promise = new Promise<T>((yes, no) => {
    resolve = yes;
    reject = no;
  });
  return { promise, resolve, reject };
}

function setup() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  const result = {
    current: undefined as unknown as ReturnType<typeof useAuth>,
  };
  function Probe() {
    result.current = useAuth();
    return null;
  }
  render(
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <Probe />
      </AuthProvider>
    </QueryClientProvider>,
  );
  return { result, queryClient };
}

beforeEach(() => {
  jest.clearAllMocks();
});

test('login awaits both real React Query mutation and authenticated current-user refresh', async () => {
  const exchange = deferred<any>();
  const me = deferred<any>();
  const request = CLIENT.request as jest.Mock;
  request
    .mockResolvedValueOnce({ data: null })
    .mockReturnValueOnce(exchange.promise)
    .mockReturnValueOnce(me.promise);
  const { result, queryClient } = setup();
  await waitFor(() => expect(result.current.currentUser).toBeNull());
  let settled = false;
  let login!: Promise<void>;
  act(() => {
    login = result.current.loginWithToken('private-id-token');
    login.then(() => {
      settled = true;
    });
  });
  await waitFor(() => expect(request).toHaveBeenCalledTimes(2));
  expect(settled).toBe(false);
  expect(request.mock.calls[1][0]).toMatchObject({
    method: 'post',
    url: '/auth/tokens',
    params: { ext: 'cookie' },
  });
  await act(async () => {
    exchange.resolve({ data: { access_token: 'uc-token' } });
  });
  await waitFor(() => expect(request).toHaveBeenCalledTimes(3));
  expect(settled).toBe(false);
  await act(async () => {
    me.resolve({ data: { id: 'enabled-user', displayName: 'Test User' } });
    await login;
  });
  expect(result.current.currentUser?.id).toBe('enabled-user');
  expect(settled).toBe(true);
  queryClient.clear();
});

test.each(['exchange', 'refresh', 'missing user'])(
  'login fails on %s without false success',
  async (stage) => {
    const request = CLIENT.request as jest.Mock;
    request.mockReset().mockResolvedValueOnce({ data: null });
    if (stage === 'exchange')
      request.mockRejectedValueOnce(new Error('private provider details'));
    else {
      request.mockResolvedValueOnce({ data: { access_token: 'uc-token' } });
      if (stage === 'refresh')
        request.mockRejectedValueOnce(new Error('private user details'));
      else request.mockResolvedValueOnce({ data: null });
    }
    const { result, queryClient } = setup();
    await waitFor(() => expect(result.current.currentUser).toBeNull());
    await act(async () => {
      await expect(result.current.loginWithToken('id-token')).rejects.toThrow(
        'Login failed',
      );
    });
    expect(mockNotification).toHaveBeenCalledWith(
      expect.stringContaining('Login failed'),
      'error',
    );
    queryClient.clear();
  },
);

test('logout awaits UC, clears IdP and cached catalog data, then refreshes the user', async () => {
  const request = CLIENT.request as jest.Mock;
  request.mockReset().mockResolvedValueOnce({ data: { id: 'enabled-user' } });
  const logout = deferred<any>();
  request
    .mockReturnValueOnce(logout.promise)
    .mockResolvedValueOnce({ data: null });
  const { result, queryClient } = setup();
  queryClient.setQueryData(['catalogs'], ['private-catalog']);
  await waitFor(() =>
    expect(result.current.currentUser?.id).toBe('enabled-user'),
  );
  let pending!: Promise<void>;
  act(() => {
    pending = result.current.logout();
  });
  await waitFor(() => expect(request).toHaveBeenCalledTimes(2));
  expect(clearOidcSession).not.toHaveBeenCalled();
  await act(async () => {
    logout.resolve({ data: {} });
    await pending;
  });
  expect(clearOidcSession).toHaveBeenCalledTimes(1);
  expect(queryClient.getQueryData(['catalogs'])).toBeUndefined();
  await waitFor(() => expect(result.current.currentUser).toBeNull());
  queryClient.clear();
});

test('logout failure does not pretend that the UC session was cleared', async () => {
  (CLIENT.request as jest.Mock)
    .mockReset()
    .mockResolvedValueOnce({ data: { id: 'enabled-user' } })
    .mockRejectedValueOnce(new Error('request failed'));
  const { result, queryClient } = setup();
  await waitFor(() =>
    expect(result.current.currentUser?.id).toBe('enabled-user'),
  );
  await act(async () => {
    await expect(result.current.logout()).rejects.toThrow('Logout failed');
  });
  expect(mockNotification).toHaveBeenCalledWith(
    expect.stringContaining('Logout failed'),
    'error',
  );
  expect(clearOidcSession).not.toHaveBeenCalled();
  expect(result.current.currentUser?.id).toBe('enabled-user');
  queryClient.clear();
});

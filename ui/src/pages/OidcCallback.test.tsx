import { StrictMode } from 'react';
import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import OidcCallback from './OidcCallback';
import {
  completeOidcSignin,
  getOidcManager,
  startOidcSignin,
} from '../components/login/oidc';
import OidcAuthButton from '../components/login/OidcAuthButton';

const mockLogin = jest.fn();
jest.mock('../context/auth-context', () => ({
  useAuth: () => ({ loginWithToken: mockLogin }),
}));
jest.mock('../components/login/oidc', () => ({
  completeOidcSignin: jest.fn(),
  getOidcManager: jest.fn(),
  startOidcSignin: jest.fn(),
  oidcEnabled: true,
  oidcProviderName: 'Microsoft',
}));

function callbackPage() {
  return render(
    <StrictMode>
      <MemoryRouter initialEntries={['/login/callback']}>
        <Routes>
          <Route path="/login/callback" element={<OidcCallback />} />
          <Route path="/" element={<div>Catalog page</div>} />
        </Routes>
      </MemoryRouter>
    </StrictMode>,
  );
}

beforeEach(() => {
  jest.clearAllMocks();
  require('../components/login/oidc').oidcEnabled = true;
  (getOidcManager as jest.Mock).mockReturnValue({});
  (startOidcSignin as jest.Mock).mockResolvedValue(undefined);
});

test('disabled OIDC does not render a login button or construct a client', () => {
  require('../components/login/oidc').oidcEnabled = false;
  render(<OidcAuthButton />);
  expect(screen.queryByRole('button')).toBeNull();
  expect(getOidcManager).not.toHaveBeenCalled();
});

test('callback is reachable unauthenticated and navigation waits for the exchange', async () => {
  let resolve!: () => void;
  (completeOidcSignin as jest.Mock).mockReturnValue(
    new Promise<void>((done) => {
      resolve = done;
    }),
  );
  callbackPage();
  expect(screen.queryByText('Catalog page')).toBeNull();
  expect(completeOidcSignin).toHaveBeenCalledWith(mockLogin);
  await act(async () => {
    resolve();
  });
  expect(await screen.findByText('Catalog page')).toBeTruthy();
});

test('callback rejection is visible, stays off the catalog, and permits a fresh retry without logging secrets', async () => {
  const logs = [jest.spyOn(console, 'log'), jest.spyOn(console, 'error')];
  (completeOidcSignin as jest.Mock).mockRejectedValue(
    new Error('private-id-token'),
  );
  callbackPage();
  expect(await screen.findByText(/Sign-in failed/)).toBeTruthy();
  expect(screen.queryByText('Catalog page')).toBeNull();
  fireEvent.click(screen.getByText('Continue with Microsoft'));
  await waitFor(() => expect(startOidcSignin).toHaveBeenCalledTimes(1));
  for (const log of logs) {
    expect(log.mock.calls.flat().join(' ')).not.toContain('private-id-token');
    log.mockRestore();
  }
});

test('misconfigured provider shows an actionable error and disabled button', () => {
  (getOidcManager as jest.Mock).mockReturnValue(undefined);
  render(<OidcAuthButton />);
  expect(screen.getByRole('button').hasAttribute('disabled')).toBe(true);
  expect(screen.getByRole('alert').textContent).toContain(
    'OIDC is not configured',
  );
});

test('redirect failure is visible and retryable', async () => {
  (startOidcSignin as jest.Mock).mockRejectedValue(
    new Error('private-provider-response'),
  );
  render(<OidcAuthButton />);
  fireEvent.click(screen.getByRole('button'));
  expect((await screen.findByRole('alert')).textContent).toContain(
    'Sign-in could not start',
  );
  expect(screen.getByRole('button').hasAttribute('disabled')).toBe(false);
});

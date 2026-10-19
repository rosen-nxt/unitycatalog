import { act, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import Login from './Login';

const mockLogin = jest.fn();
jest.mock('../context/auth-context', () => ({
  useAuth: () => ({ loginWithToken: mockLogin }),
}));
jest.mock('../components/login/OidcAuthButton', () => () => (
  <div>OIDC provider</div>
));
jest.mock('../components/login/oidc', () => ({ oidcEnabled: false }));
jest.mock(
  '../components/login/GoogleAuthButton',
  () =>
    ({ onGoogleSignIn }: any) => (
      <button onClick={() => onGoogleSignIn('google-token')}>Google</button>
    ),
);
jest.mock('../components/login/OktaAuthButton', () => ({ onSuccess }: any) => (
  <button onClick={() => onSuccess({ idToken: { idToken: 'okta-token' } })}>
    Okta
  </button>
));
jest.mock('../components/login/KeycloakAuthButton', () => () => (
  <div>Keycloak placeholder</div>
));

const originalEnv = process.env;
beforeEach(() => {
  process.env = { ...originalEnv, REACT_APP_GOOGLE_AUTH_ENABLED: 'true' };
  jest.clearAllMocks();
});
afterEach(() => {
  process.env = originalEnv;
});

function page(from: unknown = '/') {
  render(
    <MemoryRouter initialEntries={[{ pathname: '/login', state: { from } }]}>
      <Routes>
        <Route path="/login" element={<Login />} />
        <Route path="/" element={<div>Catalog page</div>} />
        <Route path="/data/catalog" element={<div>Catalog detail</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

test('Google waits for exchange before navigating to a local destination', async () => {
  let finish!: () => void;
  mockLogin.mockReturnValue(
    new Promise<void>((resolve) => {
      finish = resolve;
    }),
  );
  page('/data/catalog');
  fireEvent.click(screen.getByText('Google'));
  expect(mockLogin).toHaveBeenCalledWith('google-token');
  expect(screen.queryByText('Catalog detail')).toBeNull();
  await act(async () => {
    finish();
  });
  expect(await screen.findByText('Catalog detail')).toBeTruthy();
});

test.each([
  'https://evil.example',
  '//evil.example',
  '/\\evil.example',
  '/\t/evil.example',
  '//[',
  '/login/callback',
])('rejects unsafe redirect %s', async (from) => {
  mockLogin.mockResolvedValue(undefined);
  page(from);
  fireEvent.click(screen.getByText('Google'));
  expect(await screen.findByText('Catalog page')).toBeTruthy();
});

test('failed exchange stays on login and displays a generic error', async () => {
  mockLogin.mockRejectedValue(new Error('private-id-token'));
  page();
  fireEvent.click(screen.getByText('Google'));
  expect(await screen.findByText(/Sign-in failed/)).toBeTruthy();
  expect(screen.queryByText('Catalog page')).toBeNull();
});

test('Okta uses the same awaited exchange rather than logging tokens', async () => {
  process.env.REACT_APP_OKTA_AUTH_ENABLED = 'true';
  mockLogin.mockResolvedValue(undefined);
  page();
  fireEvent.click(screen.getByText('Okta'));
  expect(await screen.findByText('Catalog page')).toBeTruthy();
  expect(mockLogin).toHaveBeenCalledWith('okta-token');
});

test('disabled providers display configuration guidance', () => {
  process.env.REACT_APP_GOOGLE_AUTH_ENABLED = 'false';
  page();
  expect(screen.getByText('Auth providers have not been enabled')).toBeTruthy();
  expect(screen.queryByText('Google')).toBeNull();
});

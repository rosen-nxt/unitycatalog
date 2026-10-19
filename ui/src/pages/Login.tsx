import { Alert, Flex, Layout, Typography } from 'antd';
import React, { useState } from 'react';
import GoogleAuthButton from '../components/login/GoogleAuthButton';
import OktaAuthButton from '../components/login/OktaAuthButton';
import { useAuth } from '../context/auth-context';
import KeycloakAuthButton from '../components/login/KeycloakAuthButton';
import { useNavigate, useLocation } from 'react-router-dom';
import OidcAuthButton from '../components/login/OidcAuthButton';
import { oidcEnabled } from '../components/login/oidc';

function safeDestination(requested: unknown): string {
  if (typeof requested !== 'string' || !requested.startsWith('/')) return '/';
  try {
    const url = new URL(requested, window.location.origin);
    return url.origin === window.location.origin &&
      url.pathname !== '/login/callback'
      ? url.pathname + url.search + url.hash
      : '/';
  } catch {
    return '/';
  }
}

export default function LoginPage() {
  const { loginWithToken } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const from = safeDestination(location.state?.from);
  const [failed, setFailed] = useState(false);
  const googleEnabled = process.env.REACT_APP_GOOGLE_AUTH_ENABLED === 'true';
  const oktaEnabled = process.env.REACT_APP_OKTA_AUTH_ENABLED === 'true';
  const keycloakEnabled =
    process.env.REACT_APP_KEYCLOAK_AUTH_ENABLED === 'true';

  const handleSignIn = async (idToken: string) => {
    setFailed(false);
    try {
      if (!idToken) throw new Error('Missing ID token');
      await loginWithToken(idToken);
      navigate(from, { replace: true });
    } catch {
      setFailed(true);
    }
  };

  return (
    <Layout
      hasSider={false}
      style={{
        height: '100vh',
        width: '100vw',
        background: 'linear-gradient(#131D35,#252342,#1E2E3A)',
      }}
    >
      <Flex
        vertical={true}
        align={'center'}
        justify={'center'}
        gap={'middle'}
        style={{ height: '100%', width: '100%' }}
      >
        <div>
          <img
            src="/uc-logo-horiz-reverse.svg"
            height={32}
            alt="uc-logo-horizontal"
          />
        </div>
        <div
          style={{
            minHeight: 276,
            width: 400,
            backgroundColor: '#F6F7F9',
            borderRadius: '16px',
          }}
        >
          <Flex
            vertical={true}
            align={'center'}
            justify={'center'}
            gap={'middle'}
            style={{ padding: 24 }}
          >
            <Typography.Title level={4}>
              Login to Unity Catalog
            </Typography.Title>
            {failed && (
              <Alert type="error" message="Sign-in failed. Please try again." />
            )}
            <OidcAuthButton />
            {googleEnabled && (
              <GoogleAuthButton onGoogleSignIn={handleSignIn} />
            )}
            {oktaEnabled && (
              <OktaAuthButton
                onSuccess={(tokens: any) =>
                  handleSignIn(tokens?.idToken?.idToken)
                }
                onError={() => setFailed(true)}
              />
            )}
            {keycloakEnabled && <KeycloakAuthButton />}
            {!googleEnabled &&
              !oktaEnabled &&
              !keycloakEnabled &&
              !oidcEnabled && (
                <Typography>Auth providers have not been enabled</Typography>
              )}
          </Flex>
        </div>
      </Flex>
    </Layout>
  );
}

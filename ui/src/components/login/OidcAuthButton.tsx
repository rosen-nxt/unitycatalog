import { Avatar, Button } from 'antd';
import { useCallback, useEffect, useRef, useState } from 'react';
import { UserManager, WebStorageStateStore } from 'oidc-client-ts';

interface OidcAuthButtonProps {
  onSignIn: (idToken: string) => void;
}

function hasAuthParams(location: Location = window.location): boolean {
  // response_mode: query
  let searchParams = new URLSearchParams(location.search);
  if (
    (searchParams.get('code') || searchParams.get('error')) &&
    searchParams.get('state')
  ) {
    return true;
  }

  // response_mode: fragment
  searchParams = new URLSearchParams(location.hash.replace('#', '?'));
  if (
    (searchParams.get('code') || searchParams.get('error')) &&
    searchParams.get('state')
  ) {
    return true;
  }

  return false;
}

const issuer = process.env.REACT_APP_OIDC_ISSUER || '';
const clientId = process.env.REACT_APP_OIDC_CLIENT_ID || '';
const redirectUri =
  process.env.REACT_APP_OIDC_REDIRECT_URI ||
  `${window.location.origin}/login/callback`;
const scopes = process.env.REACT_APP_OIDC_SCOPES || 'openid profile email';
const providerName = process.env.REACT_APP_OIDC_PROVIDER_NAME || 'OIDC';

const oidcConfig = {
  issuer: issuer,
  clientId: clientId,
  redirectUri: redirectUri,
  scopes: scopes,
  providerName: providerName,
  isConfigured: !!(issuer && clientId),
};

export default function OidcAuthButton({ onSignIn }: OidcAuthButtonProps) {
  const didInitialize = useRef(false);
  const [isInitializing, setIsInitializing] = useState(false);

  const [userManager] = useState(() => {
    return new UserManager({
      authority: oidcConfig.issuer,
      client_id: oidcConfig.clientId,
      redirect_uri: oidcConfig.redirectUri,
      response_type: 'code',
      scope: oidcConfig.scopes,
      userStore: new WebStorageStateStore({ store: window.sessionStorage }),
      automaticSilentRenew: false,
      loadUserInfo: true,
    });
  });

  useEffect(() => {
    if (!userManager || didInitialize.current) {
      return;
    }
    didInitialize.current = true;

    if (!oidcConfig.isConfigured) {
      setIsInitializing(false);
      return;
    }

    // Handle callback if we're returning from the OIDC provider
    const handleCallback = async () => {
      if (hasAuthParams(window.location)) {
        try {
          console.log('Processing OIDC callback...');
          const user = await userManager.signinCallback();
          console.log('OIDC callback successful, user:', user);

          if (user && user.id_token) {
            // Call the onSignIn callback with the ID token
            onSignIn(user.id_token);
          } else {
            console.error('No ID token received from OIDC provider');
          }
        } catch (error) {
          console.error('OIDC callback error:', error);
        }
      }
    };

    handleCallback();
  }, [onSignIn, userManager]);

  const handleLogin = useCallback(async () => {
    if (!userManager) {
      console.error('UserManager not initialized');
      return;
    }

    try {
      console.log('Initiating OIDC login redirect...');
      await userManager.signinRedirect();
    } catch (error) {
      console.error('OIDC login error:', error);
    }
  }, [userManager]);

  if (!oidcConfig.isConfigured || isInitializing) {
    return null;
  }

  return (
    <Button
      icon={
        <Avatar
          shape={'square'}
          style={{
            width: 20,
            height: 20,
            marginRight: 16,
            backgroundColor: '#0078D4',
            color: 'white',
            fontSize: 12,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
          }}
        >
          {oidcConfig.providerName.substring(0, 2).toUpperCase()}
        </Avatar>
      }
      iconPosition={'start'}
      style={{ width: 240, height: 40, justifyContent: 'flex-start' }}
      onClick={handleLogin}
    >
      Continue with {oidcConfig.providerName}
    </Button>
  );
}

import { Button, Typography } from 'antd';
import { useState } from 'react';
import {
  getOidcManager,
  oidcEnabled,
  oidcProviderName,
  startOidcSignin,
} from './oidc';

export default function OidcAuthButton() {
  const [error, setError] = useState(false);
  const [pending, setPending] = useState(false);
  if (!oidcEnabled) return null;
  const configured = !!getOidcManager();

  const signIn = async () => {
    setError(false);
    setPending(true);
    try {
      await startOidcSignin();
    } catch {
      setError(true);
      setPending(false);
    }
  };

  return (
    <>
      <Button disabled={!configured} loading={pending} onClick={signIn}>
        Continue with {oidcProviderName}
      </Button>
      {!configured && (
        <Typography.Text role="alert">
          OIDC is not configured. Contact your administrator.
        </Typography.Text>
      )}
      {error && (
        <Typography.Text role="alert">
          Sign-in could not start. Please try again.
        </Typography.Text>
      )}
    </>
  );
}

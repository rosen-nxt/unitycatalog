import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Alert, Flex, Typography } from 'antd';
import { useAuth } from '../context/auth-context';
import { completeOidcSignin } from '../components/login/oidc';
import OidcAuthButton from '../components/login/OidcAuthButton';

export default function OidcCallback() {
  const { loginWithToken } = useAuth();
  const navigate = useNavigate();
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let mounted = true;
    completeOidcSignin(loginWithToken).then(
      () => {
        if (mounted) navigate('/', { replace: true });
      },
      () => {
        if (mounted) setFailed(true);
      },
    );
    return () => {
      mounted = false;
    };
  }, [loginWithToken, navigate]);

  return (
    <Flex vertical align="center" gap="middle" style={{ padding: 48 }}>
      {failed ? (
        <>
          <Alert
            type="error"
            showIcon
            message="Sign-in failed. Please try again or contact your administrator."
          />
          <OidcAuthButton />
        </>
      ) : (
        <Typography>Completing sign-in…</Typography>
      )}
    </Flex>
  );
}

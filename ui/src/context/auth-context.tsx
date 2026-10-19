import React, { useCallback, useMemo } from 'react';
import {
  useGetCurrentUser,
  useLoginWithToken,
  useLogoutCurrentUser,
  UserInterface,
} from '../hooks/user';
import { GrantType, TokenType } from '../types/api/control.gen';
import { useNotification } from '../utils/NotificationContext';
import { clearOidcSession } from '../components/login/oidc';
import { useQueryClient } from '@tanstack/react-query';

interface AuthContextProps {
  loginWithToken: (idToken: string) => Promise<void>;
  logout: () => Promise<void>;
  currentUser: UserInterface | null | undefined;
}

const AuthContext = React.createContext<AuthContextProps>({
  loginWithToken: async () => {
    throw new Error('Authentication is disabled');
  },
  logout: async () => {
    throw new Error('Authentication is disabled');
  },
  currentUser: null,
});
AuthContext.displayName = 'AuthContext';

function AuthProvider(props: any) {
  const { data: currentUser, refetch } = useGetCurrentUser();
  const { mutateAsync: exchangeToken, reset: resetExchange } =
    useLoginWithToken();
  const { mutateAsync: logoutUser } = useLogoutCurrentUser();
  const queryClient = useQueryClient();
  const { setNotification } = useNotification();

  const loginWithToken = useCallback(
    async (idToken: string) => {
      try {
        await exchangeToken({
          grant_type: GrantType.TOKEN_EXCHANGE,
          requested_token_type: TokenType.ACCESS_TOKEN,
          subject_token_type: TokenType.ID_TOKEN,
          subject_token: idToken,
        });
        const result = await refetch({ throwOnError: true });
        if (!result.data) throw new Error('No authenticated user');
      } catch {
        setNotification(
          'Login failed. Please contact your system administrator.',
          'error',
        );
        throw new Error('Login failed');
      } finally {
        resetExchange();
      }
    },
    [exchangeToken, resetExchange, setNotification, refetch],
  );

  const logout = useCallback(async () => {
    try {
      await logoutUser({});
      await clearOidcSession();
      await queryClient.cancelQueries();
      queryClient.setQueryData(['getUser'], null);
      queryClient.removeQueries({
        predicate: (query) => query.queryKey[0] !== 'getUser',
      });
      await refetch({ throwOnError: true });
    } catch {
      setNotification(
        'Logout failed. Please contact your system administrator.',
        'error',
      );
      throw new Error('Logout failed');
    }
  }, [refetch, logoutUser, queryClient, setNotification]);

  const value = useMemo(
    () => ({
      loginWithToken,
      logout,
      currentUser,
    }),
    [loginWithToken, logout, currentUser],
  );

  return <AuthContext.Provider value={value} {...props} />;
}

function useAuth() {
  const context = React.useContext(AuthContext);
  if (context === undefined) {
    throw new Error(`useAuth must be used within an AuthProvider`);
  }
  return context;
}

export { AuthProvider, useAuth };

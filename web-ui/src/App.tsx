import { QueryCache, QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { BrowserRouter, Route, Routes } from 'react-router';
import { ApiError, failureKind } from './api/client';
import { queryKeys } from './api/queries';
import { AuthGate } from './components/AuthGate';
import { Shell } from './components/Shell';
import { AlertsPage } from './pages/AlertsPage';
import { CpusPage } from './pages/CpusPage';
import { CraftingPage } from './pages/CraftingPage';
import { InsightsPage } from './pages/InsightsPage';
import { NetworkSettingsPage } from './pages/NetworkSettingsPage';
import { NotFoundPage } from './pages/NotFoundPage';
import { OverviewPage } from './pages/OverviewPage';
import { PatternsPage } from './pages/PatternsPage';
import { TerminalPage } from './pages/TerminalPage';
import { SettingsPage } from './pages/SettingsPage';

const queryClient: QueryClient = new QueryClient({
  queryCache: new QueryCache({
    onError: (error, query) => {
      // A revoked device fails every request with 401: re-check the session so the pairing screen appears.
      if (failureKind(error) === 'unauthenticated' && query.queryKey[0] !== queryKeys.me[0]) {
        void queryClient.invalidateQueries({ queryKey: queryKeys.me });
      }
    },
  }),
  defaultOptions: {
    queries: {
      // Client errors (4xx) will not fix themselves by retrying.
      retry: (failureCount, error) =>
        failureCount < 1 && !(error instanceof ApiError && error.status >= 400 && error.status < 500),
      refetchOnWindowFocus: true,
    },
  },
});

export function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <AuthGate>
          <Routes>
            <Route element={<Shell />}>
              <Route index element={<OverviewPage />} />
              <Route path="terminal" element={<TerminalPage />} />
              <Route path="crafting" element={<CraftingPage />} />
              <Route path="cpus" element={<CpusPage />} />
              <Route path="patterns" element={<PatternsPage />} />
              <Route path="insights" element={<InsightsPage />} />
              <Route path="alerts" element={<AlertsPage />} />
              <Route path="settings" element={<SettingsPage />} />
              <Route path="networks/:networkId" element={<NetworkSettingsPage />} />
              <Route path="*" element={<NotFoundPage />} />
            </Route>
          </Routes>
        </AuthGate>
      </BrowserRouter>
    </QueryClientProvider>
  );
}

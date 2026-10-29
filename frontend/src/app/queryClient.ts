import { QueryClient } from '@tanstack/react-query';
import { ApiError } from '@/shared/api/ApiError';
import { tokens } from '@/shared/api/tokens';

export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // A 4xx is the server's answer, not a hiccup: asking again would not change it.
      retry: (failureCount, error) =>
        failureCount < 2 && !(error instanceof ApiError && error.status >= 400 && error.status < 500),
      refetchOnWindowFocus: false,
    },
    mutations: { retry: false },
  },
});

// Nothing cached for one user may be shown to the next (frontend-design.md §5).
tokens.onClear(() => queryClient.clear());

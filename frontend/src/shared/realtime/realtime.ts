import { useEffect, useRef, useSyncExternalStore } from 'react';
import { onTokenRefreshed, refreshAccessToken } from '@/shared/api/client';
import { tokens } from '@/shared/api/tokens';
import { config } from '@/shared/config';
import { RealtimeConnection, type RealtimeStatus } from './connection';
import type { RealtimeMessage } from './protocol';

/** Refresh first when the token would expire during the handshake. */
const FRESH_FOR_MS = 30 * 1000;

/** The tab's one realtime connection. */
export const realtime = new RealtimeConnection({
  url: config.wsUrl,
  accessToken: async () => (tokens.expiresWithin(FRESH_FOR_MS) ? refreshAccessToken() : tokens.accessToken()),
  refreshToken: refreshAccessToken,
  onTokenRefreshed,
});

tokens.onClear(() => realtime.stop());

/** Keeps the connection open while the calling layout is mounted (customer and driver areas). */
export function useRealtimeSession(): void {
  useEffect(() => {
    realtime.start();
    return () => realtime.stop();
  }, []);
}

export function useRealtimeStatus(): RealtimeStatus {
  return useSyncExternalStore(
    (onChange) => realtime.onStatus(onChange),
    () => realtime.getStatus(),
  );
}

/** Calls `handler` with every server message; the latest handler is used without resubscribing. */
export function useRealtimeMessages(handler: (message: RealtimeMessage) => void): void {
  const latest = useRef(handler);
  useEffect(() => {
    latest.current = handler;
  });
  useEffect(() => realtime.subscribe((message) => latest.current(message)), []);
}

/** Calls `handler` whenever events may have been missed and the snapshot should be reloaded. */
export function useRealtimeResync(handler: () => void): void {
  const latest = useRef(handler);
  useEffect(() => {
    latest.current = handler;
  });
  useEffect(() => realtime.onResync(() => latest.current()), []);
}

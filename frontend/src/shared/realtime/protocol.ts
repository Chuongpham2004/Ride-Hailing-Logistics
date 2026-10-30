/*
 * WebSocket messages as in contracts/websocket/. Every message, both ways, has the same envelope;
 * server messages always carry `sequence`, one more than the previous message on the connection.
 */

export interface RealtimeMessage<T = Record<string, unknown>> {
  messageId: string;
  type: string;
  version: number;
  sentAt: string;
  /** Server messages only: +1 per message on this connection; a gap means something was lost. */
  sequence?: number;
  correlationId?: string;
  /** Forwarded domain events: the aggregate's version, to drop stale updates after a snapshot. */
  aggregateVersion?: number;
  data: T;
}

export interface SessionReady {
  sessionId: string;
  userId: string;
  roles: string[];
  tokenExpiresAt: string;
  heartbeatIntervalSeconds: number;
}

export interface ErrorData {
  code: string;
  message: string;
  inReplyTo?: string;
}

export interface TripSubscribed {
  inReplyTo?: string;
  tripId: string;
  participant: 'CUSTOMER' | 'DRIVER';
  status: string;
  driverId?: string;
}

export interface TripUnsubscribed {
  tripId: string;
  reason: 'TRIP_ENDED' | 'CLIENT_REQUEST' | string;
}

export interface TripDriverLocation {
  tripId: string;
  driverId: string;
  sequence: number;
  latitude: number;
  longitude: number;
  accuracyMeters?: number;
  headingDegrees?: number;
  speedMetersPerSecond?: number;
  serverTimestamp: string;
}

/** Domain events the gateway forwards about a trip; data is the event payload (contracts/events). */
export const TRIP_EVENT_TYPES = new Set([
  'TRIP_REQUESTED',
  'TRIP_ACCEPTED',
  'TRIP_STATUS_CHANGED',
  'TRIP_COMPLETED',
  'TRIP_CANCELLED',
]);

/** Close codes the gateway uses (realtime-gateway CloseCodes). */
export const CLOSE_TOKEN = 4401;
export const CLOSE_HEARTBEAT = 4408;

export function newMessage<T>(type: string, data: T): RealtimeMessage<T> {
  return { messageId: crypto.randomUUID(), type, version: 1, sentAt: new Date().toISOString(), data };
}

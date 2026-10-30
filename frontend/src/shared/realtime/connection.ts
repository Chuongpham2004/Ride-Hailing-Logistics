import {
  CLOSE_TOKEN,
  newMessage,
  type ErrorData,
  type RealtimeMessage,
  type SessionReady,
  type TripUnsubscribed,
} from './protocol';

export type RealtimeStatus = 'idle' | 'connecting' | 'open' | 'reconnecting' | 'closed';

export interface ConnectionDeps {
  url: string;
  /** A usable access token, refreshed first when close to expiry; null when signed out. */
  accessToken: () => Promise<string | null>;
  /** Forces a refresh (after a 4401 close or before the token runs out); null when signed out. */
  refreshToken: () => Promise<string | null>;
  /** Registers for token refreshes made anywhere in the app; returns the unsubscribe function. */
  onTokenRefreshed: (listener: (token: string) => void) => () => void;
  createSocket?: (url: string) => WebSocket;
}

/** Waits between reconnect attempts, in ms (frontend-design.md §6). */
const BACKOFF = [1000, 2000, 5000, 10000];
/** The gateway learns about a new trip from Kafka a moment after it is booked. */
const SUBSCRIBE_RETRY_MS = 1500;
const SUBSCRIBE_ATTEMPTS = 6;
/** Ask for a new token this long before the gateway would close the session. */
const RENEW_AHEAD_MS = 60 * 1000;
const SEEN_LIMIT = 200;

type Listener = (message: RealtimeMessage) => void;

/**
 * One WebSocket per signed-in tab (frontend-design.md §6): authenticates with the access token,
 * keeps the session alive with PING, renews the token with AUTH, reconnects with backoff and
 * re-subscribes to followed trips. Listeners get every server message once (duplicates dropped by
 * messageId); `onResync` fires after every (re)connect and after a sequence gap, when the screens
 * must reload their REST snapshot because events may have been missed.
 */
export class RealtimeConnection {
  private status: RealtimeStatus = 'idle';
  private socket: WebSocket | null = null;
  private started = false;
  /** Bumped by stop(): a connect() still waiting for its token from an older run must not open a socket. */
  private run = 0;
  private attempt = 0;
  private tokenRefusals = 0;
  private lastSequence: number | null = null;
  private readonly seen: string[] = [];
  private readonly seenSet = new Set<string>();
  private readonly trips = new Set<string>();
  private readonly pendingSubscribes = new Map<string, { tripId: string; attempt: number }>();
  private readonly listeners = new Set<Listener>();
  private readonly statusListeners = new Set<(status: RealtimeStatus) => void>();
  private readonly resyncListeners = new Set<() => void>();
  private timers = new Set<ReturnType<typeof setTimeout>>();
  private heartbeat: ReturnType<typeof setInterval> | null = null;
  private renewTimer: ReturnType<typeof setTimeout> | null = null;
  private stopTokenListener: (() => void) | null = null;

  private readonly deps: ConnectionDeps;

  constructor(deps: ConnectionDeps) {
    this.deps = deps;
  }

  getStatus(): RealtimeStatus {
    return this.status;
  }

  start(): void {
    if (this.started) return;
    this.started = true;
    this.attempt = 0;
    this.tokenRefusals = 0;
    this.stopTokenListener = this.deps.onTokenRefreshed((token) => this.sendAuth(token));
    void this.connect();
  }

  stop(): void {
    this.started = false;
    this.run += 1;
    this.stopTokenListener?.();
    this.stopTokenListener = null;
    this.clearTimers();
    this.trips.clear();
    this.pendingSubscribes.clear();
    const socket = this.socket;
    this.socket = null;
    socket?.close(1000, 'CLIENT_STOP');
    this.setStatus('idle');
  }

  subscribe(listener: Listener): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  onStatus(listener: (status: RealtimeStatus) => void): () => void {
    this.statusListeners.add(listener);
    return () => this.statusListeners.delete(listener);
  }

  onResync(listener: () => void): () => void {
    this.resyncListeners.add(listener);
    return () => this.resyncListeners.delete(listener);
  }

  /** Follows a trip (driver location for the customer); kept across reconnects until unfollowed. */
  followTrip(tripId: string): void {
    if (this.trips.has(tripId)) return;
    this.trips.add(tripId);
    if (this.status === 'open') this.sendSubscribe(tripId, 1);
  }

  unfollowTrip(tripId: string): void {
    if (!this.trips.delete(tripId)) return;
    for (const [id, pending] of this.pendingSubscribes) {
      if (pending.tripId === tripId) this.pendingSubscribes.delete(id);
    }
    if (this.status === 'open') this.send('UNSUBSCRIBE_TRIP', { tripId });
  }

  /** Sends a client message when the session is open; false when it could not be sent. */
  send(type: string, data: object): boolean {
    if (this.status !== 'open' || !this.socket) return false;
    this.socket.send(JSON.stringify(newMessage(type, data)));
    return true;
  }

  private async connect(): Promise<void> {
    const run = this.run;
    this.setStatus(this.attempt === 0 ? 'connecting' : 'reconnecting');
    let token: string | null;
    try {
      token = await this.deps.accessToken();
    } catch {
      // Server unreachable for the refresh: try again later like any other failure.
      if (run === this.run) this.scheduleReconnect();
      return;
    }
    if (!this.started || run !== this.run || this.socket) return;
    if (!token) {
      this.giveUp();
      return;
    }

    const url = `${this.deps.url}?access_token=${encodeURIComponent(token)}`;
    const socket = this.deps.createSocket ? this.deps.createSocket(url) : new WebSocket(url);
    this.socket = socket;
    this.lastSequence = null;
    socket.onmessage = (event) => {
      if (this.socket === socket) this.receive(event.data);
    };
    socket.onclose = (event) => {
      if (this.socket === socket) this.closed(event.code);
    };
  }

  private receive(raw: unknown): void {
    let message: RealtimeMessage;
    try {
      message = JSON.parse(String(raw)) as RealtimeMessage;
    } catch {
      return;
    }
    if (typeof message.sequence === 'number') {
      const gap = this.lastSequence !== null && message.sequence !== this.lastSequence + 1;
      this.lastSequence = message.sequence;
      if (gap && this.status === 'open') this.emitResync();
    }
    if (this.seenSet.has(message.messageId)) return;
    this.remember(message.messageId);

    switch (message.type) {
      case 'SESSION_READY':
        this.ready(message.data as unknown as SessionReady);
        break;
      case 'AUTH_REFRESHED':
        this.scheduleRenew((message.data as { tokenExpiresAt?: string }).tokenExpiresAt);
        break;
      case 'ERROR':
        this.error(message.data as unknown as ErrorData);
        break;
      case 'TRIP_SUBSCRIBED':
        this.forgetPending((message.data as { inReplyTo?: string }).inReplyTo);
        break;
      case 'TRIP_UNSUBSCRIBED': {
        const data = message.data as unknown as TripUnsubscribed;
        if (data.reason === 'TRIP_ENDED') this.trips.delete(data.tripId);
        break;
      }
    }
    this.listeners.forEach((listener) => listener(message));
  }

  private ready(session: SessionReady): void {
    this.attempt = 0;
    this.tokenRefusals = 0;
    this.setStatus('open');
    this.clearHeartbeat();
    const every = Math.max(5, session.heartbeatIntervalSeconds || 15) * 1000;
    this.heartbeat = setInterval(() => this.send('PING', {}), every);
    this.scheduleRenew(session.tokenExpiresAt);
    this.pendingSubscribes.clear();
    this.trips.forEach((tripId) => this.sendSubscribe(tripId, 1));
    this.emitResync();
  }

  private error(error: ErrorData): void {
    const pending = error.inReplyTo ? this.pendingSubscribes.get(error.inReplyTo) : undefined;
    if (!pending || !error.inReplyTo) return;
    this.pendingSubscribes.delete(error.inReplyTo);
    const retry =
      (error.code === 'RESOURCE_NOT_FOUND' || error.code === 'DEPENDENCY_UNAVAILABLE') &&
      pending.attempt < SUBSCRIBE_ATTEMPTS;
    if (!retry) {
      // The trip is over or not ours: stop asking.
      this.trips.delete(pending.tripId);
      return;
    }
    this.later(() => {
      if (this.trips.has(pending.tripId) && this.status === 'open') {
        this.sendSubscribe(pending.tripId, pending.attempt + 1);
      }
    }, SUBSCRIBE_RETRY_MS);
  }

  private sendSubscribe(tripId: string, attempt: number): void {
    if (!this.socket) return;
    const message = newMessage('SUBSCRIBE_TRIP', { tripId });
    this.pendingSubscribes.set(message.messageId, { tripId, attempt });
    this.socket.send(JSON.stringify(message));
  }

  private forgetPending(messageId: string | undefined): void {
    if (messageId) this.pendingSubscribes.delete(messageId);
  }

  private sendAuth(token: string): void {
    this.send('AUTH', { accessToken: token });
  }

  /** Refreshes the token before the gateway closes the session; the refresh listener sends AUTH. */
  private scheduleRenew(expiresAt: string | undefined): void {
    if (this.renewTimer) clearTimeout(this.renewTimer);
    this.renewTimer = null;
    const at = expiresAt ? Date.parse(expiresAt) : NaN;
    if (Number.isNaN(at)) return;
    const delay = Math.max(0, at - Date.now() - RENEW_AHEAD_MS);
    this.renewTimer = setTimeout(() => {
      this.renewTimer = null;
      this.deps.refreshToken().catch(() => {
        // Unreachable: the gateway closes with 4401 when the token runs out and we reconnect.
      });
    }, delay);
  }

  private closed(code: number): void {
    this.socket = null;
    this.clearHeartbeat();
    if (this.renewTimer) clearTimeout(this.renewTimer);
    this.renewTimer = null;
    if (!this.started) return;
    if (code === CLOSE_TOKEN) {
      this.tokenRefusals += 1;
      if (this.tokenRefusals > 2) {
        this.giveUp();
        return;
      }
      this.setStatus('reconnecting');
      this.deps.refreshToken().then(
        (token) => (token ? void this.connect() : this.giveUp()),
        () => this.scheduleReconnect(),
      );
      return;
    }
    this.scheduleReconnect();
  }

  private scheduleReconnect(): void {
    if (!this.started) return;
    this.setStatus('reconnecting');
    const delay = BACKOFF[Math.min(this.attempt, BACKOFF.length - 1)]!;
    this.attempt += 1;
    this.later(() => {
      if (this.started && !this.socket) void this.connect();
    }, delay);
  }

  /** Signed out, or the token keeps being refused: nothing more to do until start() again. */
  private giveUp(): void {
    this.started = false;
    this.run += 1;
    this.stopTokenListener?.();
    this.stopTokenListener = null;
    this.clearTimers();
    this.setStatus('closed');
  }

  private later(task: () => void, ms: number): void {
    const id = setTimeout(() => {
      this.timers.delete(id);
      task();
    }, ms);
    this.timers.add(id);
  }

  private clearHeartbeat(): void {
    if (this.heartbeat) clearInterval(this.heartbeat);
    this.heartbeat = null;
  }

  private clearTimers(): void {
    this.clearHeartbeat();
    if (this.renewTimer) clearTimeout(this.renewTimer);
    this.renewTimer = null;
    this.timers.forEach((id) => clearTimeout(id));
    this.timers = new Set();
  }

  private remember(messageId: string): void {
    this.seen.push(messageId);
    this.seenSet.add(messageId);
    if (this.seen.length > SEEN_LIMIT) this.seenSet.delete(this.seen.shift()!);
  }

  private emitResync(): void {
    this.resyncListeners.forEach((listener) => listener());
  }

  private setStatus(status: RealtimeStatus): void {
    if (this.status === status) return;
    this.status = status;
    this.statusListeners.forEach((listener) => listener(status));
  }
}

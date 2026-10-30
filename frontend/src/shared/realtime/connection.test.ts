import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { RealtimeConnection } from './connection';
import type { RealtimeMessage } from './protocol';

class FakeSocket {
  sent: RealtimeMessage[] = [];
  closedWith: number | null = null;
  onmessage: ((event: { data: string }) => void) | null = null;
  onclose: ((event: { code: number }) => void) | null = null;
  readonly url: string;

  constructor(url: string) {
    this.url = url;
  }

  send(raw: string) {
    this.sent.push(JSON.parse(raw) as RealtimeMessage);
  }

  close(code: number) {
    this.closedWith = code;
  }

  /** Server side. */
  push(type: string, data: object, extra: Partial<RealtimeMessage> = {}) {
    this.onmessage?.({
      data: JSON.stringify({
        messageId: crypto.randomUUID(),
        type,
        version: 1,
        sentAt: new Date().toISOString(),
        sequence: ++this.sequence,
        data,
        ...extra,
      }),
    });
  }

  drop(code: number) {
    this.onclose?.({ code });
  }

  sequence = 0;
}

const READY = {
  sessionId: 's',
  userId: 'u',
  roles: ['CUSTOMER'],
  tokenExpiresAt: new Date(Date.now() + 15 * 60 * 1000).toISOString(),
  heartbeatIntervalSeconds: 15,
};

function setup() {
  const sockets: FakeSocket[] = [];
  let token = 'token-1';
  const refreshToken = vi.fn(async () => {
    token = `token-${Number(token.split('-')[1]) + 1}`;
    return token;
  });
  const connection = new RealtimeConnection({
    url: 'ws://test/ws',
    accessToken: async () => token,
    refreshToken,
    onTokenRefreshed: () => () => {},
    createSocket: (url) => {
      const socket = new FakeSocket(url);
      sockets.push(socket);
      return socket as unknown as WebSocket;
    },
  });
  const last = () => sockets[sockets.length - 1]!;
  return { connection, sockets, last, refreshToken };
}

describe('RealtimeConnection', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('connects with the token, opens on SESSION_READY and follows the trips asked for', async () => {
    const { connection, last } = setup();
    const resync = vi.fn();
    connection.onResync(resync);
    connection.followTrip('trip-1');
    connection.start();
    await vi.advanceTimersByTimeAsync(0);

    expect(last().url).toBe('ws://test/ws?access_token=token-1');
    expect(connection.getStatus()).toBe('connecting');

    last().push('SESSION_READY', READY);
    expect(connection.getStatus()).toBe('open');
    expect(resync).toHaveBeenCalledTimes(1);
    expect(last().sent).toMatchObject([{ type: 'SUBSCRIBE_TRIP', data: { tripId: 'trip-1' } }]);

    await vi.advanceTimersByTimeAsync(15_000);
    expect(last().sent.at(-1)?.type).toBe('PING');
  });

  it('asks again when the gateway does not know the new trip yet', async () => {
    const { connection, last } = setup();
    connection.start();
    await vi.advanceTimersByTimeAsync(0);
    last().push('SESSION_READY', READY);

    connection.followTrip('trip-1');
    const first = last().sent[0]!;
    last().push('ERROR', {
      code: 'RESOURCE_NOT_FOUND',
      message: 'Trip not found',
      inReplyTo: first.messageId,
    });

    await vi.advanceTimersByTimeAsync(1500);
    expect(last().sent.filter((m) => m.type === 'SUBSCRIBE_TRIP')).toHaveLength(2);
  });

  it('stops asking once the trip is over', async () => {
    const { connection, last } = setup();
    connection.start();
    await vi.advanceTimersByTimeAsync(0);
    last().push('SESSION_READY', READY);

    connection.followTrip('trip-1');
    const first = last().sent[0]!;
    last().push('ERROR', { code: 'INVALID_TRIP_STATE', message: 'over', inReplyTo: first.messageId });

    await vi.advanceTimersByTimeAsync(10_000);
    expect(last().sent.filter((m) => m.type === 'SUBSCRIBE_TRIP')).toHaveLength(1);
  });

  it('delivers each message once and reports a sequence gap as a resync', async () => {
    const { connection, last } = setup();
    const received: string[] = [];
    const resync = vi.fn();
    connection.subscribe((m) => received.push(m.type));
    connection.onResync(resync);
    connection.start();
    await vi.advanceTimersByTimeAsync(0);
    last().push('SESSION_READY', READY);

    const event = { messageId: 'event-1', aggregateVersion: 3 };
    last().push('TRIP_ACCEPTED', { tripId: 'trip-1' }, event);
    last().push('TRIP_ACCEPTED', { tripId: 'trip-1' }, event);
    expect(received.filter((t) => t === 'TRIP_ACCEPTED')).toHaveLength(1);

    last().sequence += 2;
    last().push('TRIP_STATUS_CHANGED', { tripId: 'trip-1' });
    expect(resync).toHaveBeenCalledTimes(2);
  });

  it('reconnects with backoff after the connection drops, and resubscribes', async () => {
    const { connection, sockets, last } = setup();
    connection.followTrip('trip-1');
    connection.start();
    await vi.advanceTimersByTimeAsync(0);
    last().push('SESSION_READY', READY);

    last().drop(1006);
    expect(connection.getStatus()).toBe('reconnecting');
    await vi.advanceTimersByTimeAsync(999);
    expect(sockets).toHaveLength(1);
    await vi.advanceTimersByTimeAsync(1);
    expect(sockets).toHaveLength(2);

    last().push('SESSION_READY', READY);
    expect(connection.getStatus()).toBe('open');
    expect(last().sent).toMatchObject([{ type: 'SUBSCRIBE_TRIP', data: { tripId: 'trip-1' } }]);
  });

  it('refreshes the token after a 4401 close and connects with the new one', async () => {
    const { connection, sockets, last, refreshToken } = setup();
    connection.start();
    await vi.advanceTimersByTimeAsync(0);

    last().drop(4401);
    await vi.advanceTimersByTimeAsync(0);
    expect(refreshToken).toHaveBeenCalledTimes(1);
    expect(sockets).toHaveLength(2);
    expect(last().url).toBe('ws://test/ws?access_token=token-2');
  });

  it('gives up when signed out', async () => {
    const { connection, last, refreshToken } = setup();
    refreshToken.mockResolvedValueOnce(null as unknown as string);
    connection.start();
    await vi.advanceTimersByTimeAsync(0);

    last().drop(4401);
    await vi.advanceTimersByTimeAsync(0);
    expect(connection.getStatus()).toBe('closed');
  });

  it('opens one socket when stopped and started again before the token arrives', async () => {
    const { connection, sockets } = setup();
    connection.start();
    connection.stop();
    connection.start();
    await vi.advanceTimersByTimeAsync(0);
    expect(sockets).toHaveLength(1);
  });

  it('closes the socket and forgets the trips on stop', async () => {
    const { connection, last } = setup();
    connection.followTrip('trip-1');
    connection.start();
    await vi.advanceTimersByTimeAsync(0);
    const socket = last();

    connection.stop();
    expect(socket.closedWith).toBe(1000);
    expect(connection.getStatus()).toBe('idle');
  });
});

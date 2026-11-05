import { http, HttpResponse } from 'msw';
import { describe, expect, it, vi } from 'vitest';
import { apiUrl, ok, server } from '@/test/server';
import {
  createSequence,
  distanceMeters,
  reportablePosition,
  reportLocation,
  STALE_FIX_MS,
  stepToward,
} from './location';

const socket = vi.hoisted(() => ({ open: true, sent: [] as unknown[][] }));

vi.mock('@/shared/realtime/realtime', () => ({
  realtime: {
    send: (...args: unknown[]) => {
      if (!socket.open) return false;
      socket.sent.push(args);
      return true;
    },
  },
  useRealtimeMessages: () => {},
}));

const HERE = { latitude: 10.7769, longitude: 106.7009, accuracyMeters: 8, headingDegrees: 90 };

describe('driver location', () => {
  it('numbers reports strictly upwards even within one millisecond', () => {
    const next = createSequence(() => 1000);
    expect([next(), next(), next()]).toEqual([1000, 1001, 1002]);
  });

  it('steps toward a target without overshooting it', () => {
    const to = { latitude: 10.7869, longitude: 106.7009 };
    const step = stepToward(HERE, to, 15);
    expect(distanceMeters(HERE, step)).toBeCloseTo(15, 0);
    expect(step.arrived).toBe(false);
    expect(stepToward(HERE, to, 5000)).toEqual({ ...to, arrived: true });
  });

  it('sends over the socket with sequence in the envelope, and over REST when it is down', async () => {
    socket.open = true;
    await reportLocation(HERE);
    const [type, data, extra] = socket.sent[0]!;
    expect(type).toBe('DRIVER_LOCATION_UPDATED');
    expect(data).toMatchObject({
      latitude: 10.7769,
      longitude: 106.7009,
      accuracyMeters: 8,
      headingDegrees: 90,
    });
    expect(data).not.toHaveProperty('sequence');
    const first = (extra as { sequence: number }).sequence;

    let body: Record<string, unknown> | null = null;
    server.use(
      http.post(apiUrl('/api/v1/locations/me'), async ({ request }) => {
        body = (await request.json()) as Record<string, unknown>;
        return ok({ sequence: body.sequence, outcome: 'CURRENT' });
      }),
    );
    socket.open = false;
    await reportLocation(HERE);
    expect(body).toMatchObject({ latitude: 10.7769, accuracyMeters: 8 });
    expect(body!.sequence as number).toBeGreaterThan(first);
  });

  it('fails the REST report when the server refuses it', async () => {
    socket.open = false;
    server.use(
      http.post(apiUrl('/api/v1/locations/me'), () =>
        HttpResponse.json({ code: 'BUSINESS_RULE_VIOLATION', message: 'offline' }, { status: 422 }),
      ),
    );
    await expect(reportLocation(HERE)).rejects.toMatchObject({ code: 'BUSINESS_RULE_VIOLATION' });
  });

  it('sends the time of the GPS fix, not the time of sending', async () => {
    socket.open = true;
    socket.sent.length = 0;
    const fixedAt = Date.parse('2026-11-05T10:00:00Z');
    await reportLocation({ ...HERE, fixedAt }, fixedAt + 9000);
    expect(socket.sent[0]![1]).toMatchObject({ deviceTimestamp: '2026-11-05T10:00:00.000Z' });
  });

  it('stops reporting a GPS fix once it is older than the freshness window', () => {
    const now = Date.parse('2026-11-05T10:00:00Z');
    const fix = { ...HERE, fixedAt: now - 5000 };
    expect(reportablePosition({ mode: 'gps', position: fix }, now)).toBe(fix);
    expect(
      reportablePosition({ mode: 'gps', position: { ...HERE, fixedAt: now - STALE_FIX_MS - 1 } }, now),
    ).toBeNull();
    expect(reportablePosition({ mode: 'gps', position: HERE }, now)).toBeNull();
    expect(reportablePosition({ mode: 'gps', position: null }, now)).toBeNull();
  });

  it('always reports the simulated position as current', () => {
    const now = Date.parse('2026-11-05T10:00:00Z');
    const old = { ...HERE, fixedAt: now - 10 * STALE_FIX_MS };
    expect(reportablePosition({ mode: 'simulated', position: old }, now)).toEqual({
      ...HERE,
      fixedAt: undefined,
    });
  });
});

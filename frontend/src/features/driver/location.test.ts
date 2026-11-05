import { http, HttpResponse } from 'msw';
import { describe, expect, it, vi } from 'vitest';
import { apiUrl, ok, server } from '@/test/server';
import { createSequence, distanceMeters, reportLocation, stepToward } from './location';

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
});

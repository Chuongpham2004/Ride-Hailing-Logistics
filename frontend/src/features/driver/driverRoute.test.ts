import { describe, expect, it } from 'vitest';
import type { LngLat } from '@/shared/map/polyline';
import type { Trip } from '@/shared/api/types';
import { legOf, waypointsFrom } from './driverRoute';
import { advanceAlong, distanceMeters } from './location';

const A = { latitude: 21.0, longitude: 105.8 };
// Roughly 111 m north, then 104 m east: an L-shaped road.
const CORNER = { latitude: 21.001, longitude: 105.8 };
const END = { latitude: 21.001, longitude: 105.801 };

describe('advanceAlong', () => {
  it('turns the corner within one step and keeps the distance travelled', () => {
    const next = advanceAlong(A, [CORNER], END, 150);
    expect(next.waypoints).toEqual([]);
    expect(next.arrived).toBe(false);
    // 150 m driven: the corner (~111 m) plus ~39 m east of it.
    expect(distanceMeters(CORNER, next.position)).toBeCloseTo(150 - distanceMeters(A, CORNER), 0);
    expect(next.position.latitude).toBeCloseTo(CORNER.latitude, 6);
    expect(next.heading).toBeGreaterThan(80);
    expect(next.heading).toBeLessThan(100);
  });

  it('stops short of the corner when the step is shorter', () => {
    const next = advanceAlong(A, [CORNER], END, 15);
    expect(next.waypoints).toEqual([CORNER]);
    expect(distanceMeters(A, next.position)).toBeCloseTo(15, 0);
    expect(next.heading).toBeLessThan(1);
  });

  it('arrives at the target at the end of the road', () => {
    const next = advanceAlong(A, [CORNER], END, 10_000);
    expect(next).toMatchObject({ arrived: true, waypoints: [] });
    expect(next.position).toEqual(END);
  });
});

describe('waypointsFrom', () => {
  const route: LngLat[] = [
    [105.8, 21.0],
    [105.8, 21.001],
    [105.801, 21.001],
  ];

  it('follows a route that starts at the car', () => {
    expect(waypointsFrom(route, { latitude: 21.0001, longitude: 105.8 })).toHaveLength(3);
  });

  it('ignores a route drawn for a start far from the car', () => {
    expect(waypointsFrom(route, { latitude: 21.01, longitude: 105.8 })).toEqual([]);
    expect(waypointsFrom(null, A)).toEqual([]);
  });
});

describe('legOf', () => {
  const trip = (status: Trip['status']) => ({ status }) as Trip;

  it('routes to the pickup, then to the drop-off, and keeps that road once completed', () => {
    expect(legOf(trip('ACCEPTED'))).toBe('to-pickup');
    expect(legOf(trip('PICKING_UP'))).toBe('to-pickup');
    expect(legOf(trip('ARRIVED'))).toBeNull();
    expect(legOf(trip('IN_TRIP'))).toBe('to-dropoff');
    expect(legOf(trip('COMPLETED'))).toBe('to-dropoff');
    expect(legOf(trip('CANCELLED'))).toBeNull();
  });
});

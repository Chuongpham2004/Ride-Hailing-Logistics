import { useQuery } from '@tanstack/react-query';
import { api } from '@/shared/api/client';
import type { Trip } from '@/shared/api/types';
import { decodePolyline, type LngLat } from '@/shared/map/polyline';
import { distanceMeters, useDriverLocation } from './location';

/*
 * Road routes for the driver's map (GET /api/v1/routes, pricing-service: OSRM with a straight-line
 * fallback). One request per leg of a trip: the way to the pickup starts where the car was when
 * the leg began, and is not asked again while the car moves.
 */

/** pricing-service RouteController.RouteView. */
export interface RoadRoute {
  distanceMeters: number;
  durationSeconds: number;
  /** OSRM for a road route, ESTIMATE for the straight-line fallback (no polyline). */
  source: string;
  polyline?: string;
}

export type Leg = 'to-pickup' | 'to-dropoff';

const ESTIMATE_RETRY_MS = 15_000;

type Point = { latitude: number; longitude: number };

export function legOf(trip: Trip | undefined): Leg | null {
  if (!trip) return null;
  if (trip.status === 'ACCEPTED' || trip.status === 'PICKING_UP') return 'to-pickup';
  // A completed trip keeps the road it was driven on (same query key: no new request).
  if (trip.status === 'IN_TRIP' || trip.status === 'COMPLETED') return 'to-dropoff';
  return null;
}

function routePath(from: Point, to: Point, serviceType: Trip['serviceType']): string {
  const params = new URLSearchParams({
    fromLatitude: String(from.latitude),
    fromLongitude: String(from.longitude),
    toLatitude: String(to.latitude),
    toLongitude: String(to.longitude),
    serviceType,
  });
  return `/api/v1/routes?${params}`;
}

/** The road route of the trip's current leg, decoded; null while unknown or for an estimate. */
export function useLegRoute(trip: Trip | undefined): LngLat[] | null {
  const leg = legOf(trip);
  const hasPosition = useDriverLocation((state) => state.position !== null);
  const query = useQuery({
    queryKey: ['driver', 'route', trip?.id, leg],
    queryFn: ({ signal }) => {
      // Read at fetch time, not on every render: the car's position changes every second.
      const from = leg === 'to-pickup' ? useDriverLocation.getState().position! : trip!.pickup;
      const to = leg === 'to-pickup' ? trip!.pickup : trip!.dropoff;
      return api<RoadRoute>(routePath(from, to, trip!.serviceType), { signal });
    },
    enabled: trip !== undefined && leg !== null && (leg === 'to-dropoff' || hasPosition),
    staleTime: Infinity,
    retry: 1,
    // A straight-line estimate means OSRM was unreachable just then (a cold start, say): ask again
    // a little later, until a road route comes back.
    refetchInterval: (q) => (q.state.data?.source === 'ESTIMATE' ? ESTIMATE_RETRY_MS : false),
  });
  if (!query.data?.polyline) return null;
  try {
    return decodePolyline(query.data.polyline);
  } catch {
    return null;
  }
}

/** Farther than this from the car, a route was drawn for another start: drive straight instead. */
const ROUTE_START_TOLERANCE_M = 300;

/** The route's points as the simulation's waypoints, if the route starts where the car is. */
export function waypointsFrom(route: LngLat[] | null, car: Point | null): Point[] {
  if (!route || route.length < 2 || !car) return [];
  const [lng, lat] = route[0]!;
  if (distanceMeters(car, { latitude: lat, longitude: lng }) > ROUTE_START_TOLERANCE_M) return [];
  return route.map(([longitude, latitude]) => ({ latitude, longitude }));
}

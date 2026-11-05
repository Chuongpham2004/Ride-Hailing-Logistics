import { useCallback, useEffect, useRef } from 'react';
import { create } from 'zustand';
import { createJSONStorage, persist } from 'zustand/middleware';
import { api } from '@/shared/api/client';
import { CITY_CENTER } from '@/shared/map/geocoder';
import type { MapPoint } from '@/shared/map/MapView';
import type { DriverLocationUpdate, ErrorData } from '@/shared/realtime/protocol';
import { realtime, useRealtimeMessages } from '@/shared/realtime/realtime';

/*
 * The driver's own position and how it reaches the server (frontend-design.md §6): one report
 * every 3 s while online, over the socket, or over REST while the socket is down. Matching only
 * uses fixes newer than 30 s and at most 50 m accurate (location-service max-accuracy-meters).
 *
 * A desktop browser has no real GPS (or a city-wide Wi-Fi guess), so there is also a simulated
 * position: the driver clicks the map and the car drives there below the 70 m/s location-service
 * treats as SUSPICIOUS.
 */

export const REPORT_EVERY_MS = 3000;
/** Above this the fix is not used for matching (location-service max-accuracy-meters). */
export const MAX_ACCURACY_M = 50;
/** Simulated driving speed: about 54 km/h, well under the 70 m/s plausibility limit. */
export const SIM_SPEED_MPS = 15;
const SIM_TICK_MS = 1000;
const SIM_ACCURACY_M = 8;
/** Matching ignores fixes older than this (location-service freshness window): stop sending them. */
export const STALE_FIX_MS = 30_000;
/** A parked phone may stop calling back; ask the GPS again once the last fix is this old. */
export const REFRESH_FIX_MS = 10_000;
const FRESHNESS_CHECK_MS = 5000;

export type LocationMode = 'gps' | 'simulated';
/** lost: the GPS stopped answering (tunnel, indoors); reports pause until a new fix comes in. */
export type GpsState = 'waiting' | 'ok' | 'weak' | 'lost' | 'denied' | 'unavailable';

export interface Position {
  latitude: number;
  longitude: number;
  accuracyMeters: number;
  headingDegrees?: number;
  speedMetersPerSecond?: number;
  /** When the GPS measured it (epoch ms); absent for the simulated position, which is always current. */
  fixedAt?: number;
}

interface LocationState {
  mode: LocationMode;
  gps: GpsState;
  position: Position | null;
  /** Where the simulated car is driving to. */
  target: { latitude: number; longitude: number } | null;
  /** The last problem the server reported about a location message. */
  serverError: string | null;
  lastSentAt: number | null;
  setMode: (mode: LocationMode) => void;
  /** Simulated mode: put the car here at once (only sensible while offline). */
  placeAt: (point: { latitude: number; longitude: number }) => void;
  /** Simulated mode: drive the car here. */
  driveTo: (point: { latitude: number; longitude: number }) => void;
}

export const useDriverLocation = create<LocationState>()(
  persist(
    (set, get) => ({
      mode: 'gps',
      gps: 'waiting',
      position: null,
      target: null,
      serverError: null,
      lastSentAt: null,
      setMode: (mode) =>
        set((state) => ({
          mode,
          target: null,
          // Start the simulation where the driver last was, or in the city centre.
          position:
            mode === 'simulated'
              ? (state.position ?? { ...CITY_CENTER, accuracyMeters: SIM_ACCURACY_M })
              : state.position,
        })),
      placeAt: (point) =>
        set({
          position: { ...point, accuracyMeters: SIM_ACCURACY_M, speedMetersPerSecond: 0 },
          target: null,
        }),
      driveTo: (point) => {
        if (!get().position) get().placeAt(point);
        else set({ target: point });
      },
    }),
    {
      // A reload in the middle of a simulated trip must not jump the car back to the GPS fix.
      name: 'rhl.driver.location',
      storage: createJSONStorage(() => sessionStorage),
      partialize: (state) =>
        state.mode === 'simulated'
          ? { mode: state.mode, position: state.position, target: state.target }
          : { mode: state.mode },
    },
  ),
);

const EARTH_RADIUS_M = 6_371_000;
const rad = (deg: number) => (deg * Math.PI) / 180;

/** Great-circle distance in metres. */
export function distanceMeters(
  a: { latitude: number; longitude: number },
  b: { latitude: number; longitude: number },
): number {
  const dLat = rad(b.latitude - a.latitude);
  const dLng = rad(b.longitude - a.longitude);
  const h =
    Math.sin(dLat / 2) ** 2 + Math.cos(rad(a.latitude)) * Math.cos(rad(b.latitude)) * Math.sin(dLng / 2) ** 2;
  return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1, Math.sqrt(h)));
}

/** Initial bearing from a to b, in [0, 360). */
export function bearingDegrees(
  a: { latitude: number; longitude: number },
  b: { latitude: number; longitude: number },
): number {
  const y = Math.sin(rad(b.longitude - a.longitude)) * Math.cos(rad(b.latitude));
  const x =
    Math.cos(rad(a.latitude)) * Math.sin(rad(b.latitude)) -
    Math.sin(rad(a.latitude)) * Math.cos(rad(b.latitude)) * Math.cos(rad(b.longitude - a.longitude));
  const deg = (Math.atan2(y, x) * 180) / Math.PI;
  return ((deg % 360) + 360) % 360;
}

/** Moves `from` toward `to` by at most `meters` (straight line; short steps, so flat is fine). */
export function stepToward(
  from: { latitude: number; longitude: number },
  to: { latitude: number; longitude: number },
  meters: number,
): { latitude: number; longitude: number; arrived: boolean } {
  const total = distanceMeters(from, to);
  if (total <= meters) return { ...to, arrived: true };
  const f = meters / total;
  return {
    latitude: from.latitude + (to.latitude - from.latitude) * f,
    longitude: from.longitude + (to.longitude - from.longitude) * f,
    arrived: false,
  };
}

/** Strictly increasing report numbers: the server drops a sequence it has seen (DUPLICATE). */
export function createSequence(now: () => number = Date.now): () => number {
  let last = 0;
  return () => {
    last = Math.max(now(), last + 1);
    return last;
  };
}

function toUpdate(position: Position, now: number): DriverLocationUpdate {
  const update: DriverLocationUpdate = {
    latitude: position.latitude,
    longitude: position.longitude,
    accuracyMeters: Math.max(0, position.accuracyMeters),
    // The time of the fix, not of sending: a repeated old fix must not look fresh to matching.
    deviceTimestamp: new Date(position.fixedAt ?? now).toISOString(),
  };
  if (
    position.headingDegrees !== undefined &&
    position.headingDegrees >= 0 &&
    position.headingDegrees < 360
  ) {
    update.headingDegrees = position.headingDegrees;
  }
  if (position.speedMetersPerSecond !== undefined && position.speedMetersPerSecond >= 0) {
    update.speedMetersPerSecond = position.speedMetersPerSecond;
  }
  return update;
}

const nextSequence = createSequence();

/** Sends one report: over the socket when it is open, otherwise POST /locations/me. */
export async function reportLocation(position: Position, now = Date.now()): Promise<void> {
  const update = toUpdate(position, now);
  const sequence = nextSequence();
  if (realtime.send('DRIVER_LOCATION_UPDATED', update, { sequence })) return;
  await api('/api/v1/locations/me', { method: 'POST', body: { sequence, ...update } });
}

const SERVER_ERRORS: Record<string, string> = {
  RATE_LIMIT_EXCEEDED: 'Vị trí gửi quá dày, máy chủ bỏ qua một bản tin.',
  VALIDATION_ERROR: 'Máy chủ không nhận bản tin vị trí.',
  DEPENDENCY_UNAVAILABLE: 'Máy chủ tạm chưa nhận được vị trí.',
};

/**
 * The position to report now, or null: a GPS fix only while it is younger than the freshness
 * window; the simulated position always.
 */
export function reportablePosition(
  state: Pick<LocationState, 'mode' | 'position'>,
  now = Date.now(),
): Position | null {
  const { mode, position } = state;
  if (!position) return null;
  if (mode === 'simulated') return { ...position, fixedAt: undefined };
  if (position.fixedAt === undefined || now - position.fixedAt > STALE_FIX_MS) return null;
  return position;
}

/** Follows the device GPS while the page is open (also while offline, to show the map). */
function useGpsWatch(enabled: boolean): void {
  useEffect(() => {
    if (!enabled) return;
    if (!('geolocation' in navigator)) {
      useDriverLocation.setState({ gps: 'unavailable' });
      useDriverLocation.getState().setMode('simulated');
      return;
    }
    useDriverLocation.setState({ gps: 'waiting' });
    const onFix = (fix: GeolocationPosition) => {
      const { latitude, longitude, accuracy, heading, speed } = fix.coords;
      useDriverLocation.setState({
        gps: accuracy > MAX_ACCURACY_M ? 'weak' : 'ok',
        position: {
          latitude,
          longitude,
          accuracyMeters: accuracy,
          headingDegrees: heading ?? undefined,
          speedMetersPerSecond: speed ?? undefined,
          fixedAt: fix.timestamp,
        },
      });
    };
    const onError = (error: GeolocationPositionError) => {
      if (error.code === error.PERMISSION_DENIED) {
        // Without access the driver can still test with the simulated position.
        useDriverLocation.setState({ gps: 'denied' });
        useDriverLocation.getState().setMode('simulated');
        return;
      }
      // No fix for now (tunnel, indoors, timeout): keep watching, the next fix clears it.
      useDriverLocation.setState({ gps: 'lost' });
    };
    const id = navigator.geolocation.watchPosition(onFix, onError, {
      enableHighAccuracy: true,
      maximumAge: 2000,
      timeout: 20000,
    });

    let asking = false;
    const check = setInterval(() => {
      const { position, gps } = useDriverLocation.getState();
      const age = position?.fixedAt === undefined ? Infinity : Date.now() - position.fixedAt;
      if (age > STALE_FIX_MS && gps !== 'denied' && gps !== 'waiting')
        useDriverLocation.setState({ gps: 'lost' });
      if (age > REFRESH_FIX_MS && !asking) {
        asking = true;
        navigator.geolocation.getCurrentPosition(
          (fix) => {
            asking = false;
            onFix(fix);
          },
          (error) => {
            asking = false;
            if (error.code === error.PERMISSION_DENIED) onError(error);
          },
          { enableHighAccuracy: true, maximumAge: 0, timeout: REFRESH_FIX_MS },
        );
      }
    }, FRESHNESS_CHECK_MS);
    return () => {
      navigator.geolocation.clearWatch(id);
      clearInterval(check);
    };
  }, [enabled]);
}

/** Drives the simulated car toward its target, one step a second. */
function useSimulation(enabled: boolean): void {
  useEffect(() => {
    if (!enabled) return;
    const id = setInterval(() => {
      const { position, target } = useDriverLocation.getState();
      if (!position || !target) return;
      const next = stepToward(position, target, (SIM_SPEED_MPS * SIM_TICK_MS) / 1000);
      useDriverLocation.setState({
        position: {
          latitude: next.latitude,
          longitude: next.longitude,
          accuracyMeters: SIM_ACCURACY_M,
          headingDegrees: bearingDegrees(position, target),
          speedMetersPerSecond: next.arrived ? 0 : SIM_SPEED_MPS,
        },
        target: next.arrived ? null : target,
      });
    }, SIM_TICK_MS);
    return () => clearInterval(id);
  }, [enabled]);
}

/**
 * The driver's own position for the map, and what a click on the map does in simulated mode:
 * offline it puts the car there, online it drives there (a jump would look like 1000 km/h to the
 * server and be thrown away as SUSPICIOUS).
 */
export function useMapPosition(online: boolean) {
  const position = useDriverLocation((state) => state.position);
  const mode = useDriverLocation((state) => state.mode);
  const onPick = useCallback(
    (point: MapPoint) => {
      const { placeAt, driveTo } = useDriverLocation.getState();
      if (online) driveTo(point);
      else placeAt(point);
    },
    [online],
  );
  return {
    driver: position
      ? {
          latitude: position.latitude,
          longitude: position.longitude,
          headingDegrees: position.headingDegrees,
        }
      : null,
    onPick: mode === 'simulated' ? onPick : undefined,
  };
}

/**
 * Mounted once by the driver layout. Watches the GPS (or runs the simulation) all the time, and
 * reports the position every 3 s while `online` (any availability but OFFLINE).
 */
export function useLocationReporter(online: boolean): void {
  const mode = useDriverLocation((state) => state.mode);
  useGpsWatch(mode === 'gps');
  useSimulation(mode === 'simulated');

  const sending = useRef(false);
  useEffect(() => {
    if (!online) return;
    const tick = () => {
      const position = reportablePosition(useDriverLocation.getState());
      if (!position || sending.current) return;
      sending.current = true;
      reportLocation(position)
        .then(() => useDriverLocation.setState({ lastSentAt: Date.now(), serverError: null }))
        .catch(() => useDriverLocation.setState({ serverError: 'Chưa gửi được vị trí, đang thử lại.' }))
        .finally(() => {
          sending.current = false;
        });
    };
    tick();
    const id = setInterval(tick, REPORT_EVERY_MS);
    return () => clearInterval(id);
  }, [online]);

  useRealtimeMessages((message) => {
    if (message.type !== 'ERROR') return;
    const error = message.data as unknown as ErrorData;
    const text = SERVER_ERRORS[error.code];
    if (text && online) useDriverLocation.setState({ serverError: text });
  });
}

import { config } from '@/shared/config';

/*
 * Free OpenStreetMap geocoders called straight from the browser (frontend-design.md §6.1):
 * Photon for suggestions while typing, Nominatim only to name a dropped pin. Addresses are for
 * display; the server prices from the coordinates.
 */

export interface Place {
  label: string;
  latitude: number;
  longitude: number;
}

/** Vietnam, as west, south, east, north. */
const VIETNAM_BBOX = '102.14,8.18,109.47,23.39';

interface PhotonFeature {
  geometry: { coordinates: [number, number] };
  properties: {
    name?: string;
    housenumber?: string;
    street?: string;
    district?: string;
    locality?: string;
    city?: string;
    county?: string;
    state?: string;
  };
}

function photonLabel(p: PhotonFeature['properties']): string {
  const street = [p.housenumber, p.street].filter(Boolean).join(' ');
  const parts = [p.name, street, p.district ?? p.locality, p.city ?? p.county ?? p.state];
  return [...new Set(parts.filter((part): part is string => Boolean(part)))].join(', ');
}

/** Suggestions for what the person typed, biased towards `near` when given. */
export async function searchPlaces(
  query: string,
  near: { latitude: number; longitude: number } | null,
  signal?: AbortSignal,
): Promise<Place[]> {
  const params = new URLSearchParams({ q: query, limit: '6', lang: 'default', bbox: VIETNAM_BBOX });
  if (near) {
    params.set('lat', String(near.latitude));
    params.set('lon', String(near.longitude));
  }
  const response = await fetch(`${config.geocoderUrl}/api?${params}`, { signal });
  if (!response.ok) throw new Error(`Photon answered ${response.status}`);
  const body = (await response.json()) as { features?: PhotonFeature[] };
  return (body.features ?? [])
    .map((f) => ({
      label: photonLabel(f.properties),
      longitude: f.geometry.coordinates[0],
      latitude: f.geometry.coordinates[1],
    }))
    .filter((place) => place.label.length > 0);
}

interface NominatimReverse {
  display_name?: string;
  address?: Record<string, string>;
}

let lastReverseAt = 0;

/** Nominatim's usage policy allows at most one request per second. */
async function politely(): Promise<void> {
  const wait = lastReverseAt + 1000 - Date.now();
  if (wait > 0) await new Promise((resolve) => setTimeout(resolve, wait));
  lastReverseAt = Date.now();
}

function nominatimLabel(body: NominatimReverse): string | null {
  const a = body.address ?? {};
  const street = [a.house_number, a.road].filter(Boolean).join(' ');
  const parts = [a.amenity ?? a.shop ?? a.building, street, a.suburb ?? a.quarter, a.city_district, a.city];
  const label = [...new Set(parts.filter(Boolean))].join(', ');
  if (label) return label;
  return body.display_name?.split(',').slice(0, 3).join(',').trim() || null;
}

/** A readable address for a dropped pin, or null when there is none. */
export async function reverseGeocode(
  latitude: number,
  longitude: number,
  signal?: AbortSignal,
): Promise<string | null> {
  await politely();
  const params = new URLSearchParams({
    format: 'jsonv2',
    lat: String(latitude),
    lon: String(longitude),
    zoom: '18',
    'accept-language': 'vi',
  });
  const response = await fetch(`${config.reverseGeocoderUrl}/reverse?${params}`, { signal });
  if (!response.ok) return null;
  return nominatimLabel((await response.json()) as NominatimReverse);
}

/** Shown when no address is known for a pin. */
export function pinLabel(latitude: number, longitude: number): string {
  return `Ghim tại ${latitude.toFixed(5)}, ${longitude.toFixed(5)}`;
}

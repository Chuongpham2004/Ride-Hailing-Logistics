/** [longitude, latitude], the order GeoJSON and MapLibre use. */
export type LngLat = [number, number];

/**
 * Decodes an encoded polyline (Google's algorithm). The quote's `routePolyline` comes from OSRM
 * with `geometries=polyline6`, hence precision 6 by default.
 */
export function decodePolyline(encoded: string, precision = 6): LngLat[] {
  const factor = 10 ** precision;
  const points: LngLat[] = [];
  let index = 0;
  let lat = 0;
  let lng = 0;

  const next = (): number => {
    let result = 0;
    let shift = 0;
    let byte: number;
    do {
      if (index >= encoded.length) throw new Error('Truncated polyline');
      byte = encoded.charCodeAt(index++) - 63;
      result |= (byte & 0x1f) << shift;
      shift += 5;
    } while (byte >= 0x20);
    return result & 1 ? ~(result >> 1) : result >> 1;
  };

  while (index < encoded.length) {
    lat += next();
    lng += next();
    points.push([lng / factor, lat / factor]);
  }
  return points;
}

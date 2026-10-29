import { describe, expect, it } from 'vitest';
import { decodePolyline, type LngLat } from './polyline';

/** Reference encoder, used only to build precision-6 fixtures. */
function encode(points: LngLat[], precision: number): string {
  const factor = 10 ** precision;
  let out = '';
  let prevLat = 0;
  let prevLng = 0;
  const value = (v: number) => {
    let n = v < 0 ? ~(v << 1) : v << 1;
    while (n >= 0x20) {
      out += String.fromCharCode((0x20 | (n & 0x1f)) + 63);
      n >>= 5;
    }
    out += String.fromCharCode(n + 63);
  };
  for (const [lng, lat] of points) {
    const la = Math.round(lat * factor);
    const ln = Math.round(lng * factor);
    value(la - prevLat);
    value(ln - prevLng);
    prevLat = la;
    prevLng = ln;
  }
  return out;
}

describe('decodePolyline', () => {
  it('decodes the reference example from the polyline algorithm docs (precision 5)', () => {
    expect(decodePolyline('_p~iF~ps|U_ulLnnqC_mqNvxq`@', 5)).toEqual([
      [-120.2, 38.5],
      [-120.95, 40.7],
      [-126.453, 43.252],
    ]);
  });

  it('decodes OSRM polyline6 back to [lng, lat] pairs', () => {
    // Ben Thanh market to the University of Science, District 5.
    const route: LngLat[] = [
      [106.697532, 10.772234],
      [106.69391, 10.768902],
      [106.682159, 10.762601],
    ];
    expect(decodePolyline(encode(route, 6))).toEqual(route);
  });

  it('returns no points for an empty string and throws on a truncated one', () => {
    expect(decodePolyline('')).toEqual([]);
    expect(() => decodePolyline('_p~iF~ps|U_', 5)).toThrow('Truncated');
  });
});

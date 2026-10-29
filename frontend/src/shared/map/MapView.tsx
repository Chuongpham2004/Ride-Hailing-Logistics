import 'maplibre-gl/dist/maplibre-gl.css';
// MapLibre 6 parses tiles in a module worker it loads by URL next to its own file. Vite moves the
// library into a bundle (or .vite/deps in dev) without that file, so emit it and hand over its URL.
import maplibreWorkerUrl from 'maplibre-gl/dist/maplibre-gl-worker.mjs?url';
import { useEffect, useMemo, useRef, useState } from 'react';
import Map, {
  AttributionControl,
  Layer,
  Marker,
  Source,
  type MapLayerMouseEvent,
  type MapRef,
} from 'react-map-gl/maplibre';
import { config } from '@/shared/config';
import { CITY_CENTER } from './geocoder';
import type { LngLat } from './polyline';

export interface MapPoint {
  latitude: number;
  longitude: number;
}

export interface MapViewProps {
  pickup?: MapPoint | null;
  dropoff?: MapPoint | null;
  /** Road route to draw; when absent and both stops are set, `straightLine` draws a dashed one. */
  route?: LngLat[] | null;
  straightLine?: boolean;
  /** Space kept clear of the bottom sheet when fitting the stops, in px. */
  bottomInset?: number;
  onPick?: (point: MapPoint) => void;
  className?: string;
}

const GUIDE = '#1C4E9E';

/**
 * The map every screen shares: OpenFreeMap tiles (OpenStreetMap data, attribution kept), the
 * pickup as a yellow disc ringed in asphalt and the drop-off as an asphalt square, as in the
 * prototype. Fits both stops into the part of the map the sheet leaves visible.
 */
export function MapView({
  pickup,
  dropoff,
  route,
  straightLine = false,
  bottomInset = 0,
  onPick,
  className,
}: MapViewProps) {
  const mapRef = useRef<MapRef>(null);
  // MapLibre loads asynchronously: stops known on the first render (a cached quote) are fitted
  // once the map is there, not dropped.
  const [loaded, setLoaded] = useState(false);

  const line = useMemo<LngLat[] | null>(() => {
    if (route && route.length > 1) return route;
    if (straightLine && pickup && dropoff) {
      return [
        [pickup.longitude, pickup.latitude],
        [dropoff.longitude, dropoff.latitude],
      ];
    }
    return null;
  }, [route, straightLine, pickup, dropoff]);
  const dashed = !(route && route.length > 1);

  const points = useMemo(() => {
    const all: LngLat[] = [...(line ?? [])];
    if (pickup) all.push([pickup.longitude, pickup.latitude]);
    if (dropoff) all.push([dropoff.longitude, dropoff.latitude]);
    return all;
  }, [line, pickup, dropoff]);

  useEffect(() => {
    const map = mapRef.current;
    if (!loaded || !map || points.length === 0) return;
    // A tall sheet (the address search) could leave no room at all; MapLibre then refuses to fit.
    const top = 72;
    const room = map.getContainer().clientHeight - top - 120;
    const padding = { top, left: 40, right: 40, bottom: Math.max(0, Math.min(bottomInset + 32, room)) };
    if (points.length === 1) {
      const [lng, lat] = points[0]!;
      // An offset, not padding: easeTo keeps its padding on the map, and fitBounds would then add
      // its own on top and run out of room.
      const offset: [number, number] = [0, (padding.top - padding.bottom) / 2];
      map.easeTo({ center: [lng, lat], zoom: Math.max(map.getZoom(), 15), offset, duration: 500 });
      return;
    }
    const lngs = points.map((p) => p[0]);
    const lats = points.map((p) => p[1]);
    map.fitBounds(
      [
        [Math.min(...lngs), Math.min(...lats)],
        [Math.max(...lngs), Math.max(...lats)],
      ],
      { padding, maxZoom: 16, duration: 600 },
    );
  }, [points, bottomInset, loaded]);

  const handleClick = (event: MapLayerMouseEvent) => {
    onPick?.({ latitude: event.lngLat.lat, longitude: event.lngLat.lng });
  };

  return (
    <div className={className}>
      <Map
        ref={mapRef}
        workerUrl={maplibreWorkerUrl}
        mapStyle={config.mapStyleUrl}
        initialViewState={{ ...CITY_CENTER, zoom: 13 }}
        onLoad={() => setLoaded(true)}
        attributionControl={false}
        onClick={onPick ? handleClick : undefined}
        cursor={onPick ? 'crosshair' : undefined}
        style={{ width: '100%', height: '100%' }}
      >
        {/* Required credit for OpenStreetMap data; top right, where the bottom sheet cannot cover it. */}
        <AttributionControl position="top-right" compact />
        {line && (
          <Source
            id="route"
            type="geojson"
            data={{ type: 'Feature', properties: {}, geometry: { type: 'LineString', coordinates: line } }}
          >
            {!dashed && (
              <Layer
                id="route-casing"
                type="line"
                layout={{ 'line-join': 'round', 'line-cap': 'round' }}
                paint={{ 'line-color': '#FFFFFF', 'line-width': 9 }}
              />
            )}
            <Layer
              id="route-line"
              type="line"
              layout={{ 'line-join': 'round', 'line-cap': 'round' }}
              paint={
                dashed
                  ? { 'line-color': GUIDE, 'line-width': 3, 'line-dasharray': [2, 2] }
                  : { 'line-color': GUIDE, 'line-width': 5 }
              }
            />
          </Source>
        )}
        {pickup && (
          <Marker latitude={pickup.latitude} longitude={pickup.longitude} anchor="center">
            <span
              aria-label="Điểm đón"
              role="img"
              className="flex size-6 items-center justify-center rounded-full bg-asphalt shadow"
            >
              <span className="size-4 rounded-full bg-sign" />
            </span>
          </Marker>
        )}
        {dropoff && (
          <Marker latitude={dropoff.latitude} longitude={dropoff.longitude} anchor="center">
            <span
              aria-label="Điểm đến"
              role="img"
              className="flex size-5 items-center justify-center rounded-[3px] bg-asphalt shadow"
            >
              <span className="size-2 bg-white" />
            </span>
          </Marker>
        )}
      </Map>
    </div>
  );
}

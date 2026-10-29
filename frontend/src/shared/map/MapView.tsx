import 'maplibre-gl/dist/maplibre-gl.css';
import { useEffect, useMemo, useRef } from 'react';
import Map, { Layer, Marker, Source, type MapLayerMouseEvent, type MapRef } from 'react-map-gl/maplibre';
import { config } from '@/shared/config';
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

/** District 1, Ho Chi Minh City. */
const DEFAULT_CENTER = { latitude: 10.7769, longitude: 106.7009 };

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
    if (!map || points.length === 0) return;
    const padding = { top: 72, left: 40, right: 40, bottom: bottomInset + 32 };
    if (points.length === 1) {
      const [lng, lat] = points[0]!;
      map.easeTo({ center: [lng, lat], zoom: Math.max(map.getZoom(), 15), padding, duration: 500 });
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
  }, [points, bottomInset]);

  const handleClick = (event: MapLayerMouseEvent) => {
    onPick?.({ latitude: event.lngLat.lat, longitude: event.lngLat.lng });
  };

  return (
    <div className={className}>
      <Map
        ref={mapRef}
        mapStyle={config.mapStyleUrl}
        initialViewState={{ ...DEFAULT_CENTER, zoom: 13 }}
        attributionControl={{ compact: true }}
        onClick={onPick ? handleClick : undefined}
        cursor={onPick ? 'crosshair' : undefined}
        style={{ width: '100%', height: '100%' }}
      >
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

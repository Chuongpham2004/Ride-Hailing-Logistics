/** Build-time settings (Vite env, see .env.example); defaults match the local dev setup. */
export const config = {
  apiUrl: import.meta.env.VITE_API_URL ?? 'http://localhost:8080',
  wsUrl: import.meta.env.VITE_WS_URL ?? 'ws://localhost:8085/ws',
  mapStyleUrl: import.meta.env.VITE_MAP_STYLE_URL ?? 'https://tiles.openfreemap.org/styles/liberty',
  geocoderUrl: import.meta.env.VITE_GEOCODER_URL ?? 'https://photon.komoot.io',
  reverseGeocoderUrl: import.meta.env.VITE_REVERSE_GEOCODER_URL ?? 'https://nominatim.openstreetmap.org',
} as const;

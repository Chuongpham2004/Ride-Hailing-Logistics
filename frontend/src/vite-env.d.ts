/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_API_URL?: string;
  readonly VITE_WS_URL?: string;
  readonly VITE_MAP_STYLE_URL?: string;
  readonly VITE_GEOCODER_URL?: string;
  readonly VITE_REVERSE_GEOCODER_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}

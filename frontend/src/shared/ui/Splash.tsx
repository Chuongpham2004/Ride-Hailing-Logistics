/** Shown while the session is being resumed. */
export function Splash() {
  return (
    <div role="status" className="flex h-full items-center justify-center bg-concrete">
      <span className="flex size-12 items-center justify-center rounded-full bg-asphalt">
        <span className="size-8 animate-pulse rounded-full bg-sign" />
      </span>
      <span className="sr-only">Đang tải</span>
    </div>
  );
}

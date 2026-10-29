/**
 * A pickup or delivery code drawn as a Vietnamese licence plate, so a driver can read it from the
 * saddle. Used only for those two codes. "482913" shows as "482.913".
 */
export function Plate({ code, label }: { code: string; label: string }) {
  const shown = code.length === 6 ? `${code.slice(0, 3)}.${code.slice(3)}` : code;
  return (
    <div
      role="img"
      aria-label={`${label} ${code.split('').join(' ')}`}
      className="relative flex h-23 w-63 items-center justify-center rounded-[10px] border-4 border-asphalt bg-white shadow-[inset_0_0_0_4px_#fff,inset_0_0_0_6px_var(--color-asphalt)]"
    >
      <span
        aria-hidden="true"
        className="absolute top-1/2 left-3.5 size-1.75 -translate-y-1/2 rounded-full bg-field"
      />
      <span
        aria-hidden="true"
        className="absolute top-1/2 right-3.5 size-1.75 -translate-y-1/2 rounded-full bg-field"
      />
      <span aria-hidden="true" className="font-plate text-[66px] leading-none font-bold tracking-[0.06em]">
        {shown}
      </span>
    </div>
  );
}

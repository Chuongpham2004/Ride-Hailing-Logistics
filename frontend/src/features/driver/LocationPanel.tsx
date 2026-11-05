import { MAX_ACCURACY_M, useDriverLocation } from './location';

const GPS_TEXT = {
  waiting: 'Đang lấy vị trí GPS…',
  ok: 'GPS tốt',
  weak: `GPS yếu (sai số trên ${MAX_ACCURACY_M} m): chưa dùng được để ghép chuyến`,
  lost: 'Mất tín hiệu GPS: tạm ngừng gửi vị trí, chưa nhận chuyến mới',
  denied: 'Trình duyệt chặn quyền vị trí',
  unavailable: 'Máy này không có GPS',
} as const;

/** GPS state, the last reporting problem, and the switch to the simulated position. */
export function LocationPanel({ online, className = '' }: { online: boolean; className?: string }) {
  const mode = useDriverLocation((state) => state.mode);
  const gps = useDriverLocation((state) => state.gps);
  const target = useDriverLocation((state) => state.target);
  const serverError = useDriverLocation((state) => state.serverError);
  const setMode = useDriverLocation((state) => state.setMode);
  const simulated = mode === 'simulated';
  const good = simulated || gps === 'ok';

  return (
    <div className={`pointer-events-none z-10 flex flex-col gap-1.5 *:pointer-events-auto ${className}`}>
      <div className="flex items-center gap-2 self-start rounded-full bg-white py-1.5 pr-1.5 pl-3 text-sm shadow-[0_2px_6px_rgba(43,46,51,0.18)]">
        <span aria-hidden="true" className={`size-2.5 rounded-full ${good ? 'bg-go' : 'bg-stop'}`} />
        <span role="status">
          {simulated
            ? target
              ? 'Vị trí giả lập: xe đang chạy'
              : online
                ? 'Vị trí giả lập: bấm bản đồ để chạy tới'
                : 'Vị trí giả lập: bấm bản đồ để đặt xe'
            : GPS_TEXT[gps]}
        </span>
        <button
          type="button"
          aria-pressed={simulated}
          onClick={() => setMode(simulated ? 'gps' : 'simulated')}
          className="rounded-full border border-line px-2.5 py-1 text-xs font-semibold"
        >
          {simulated ? 'Dùng GPS' : 'Giả lập'}
        </button>
      </div>
      {serverError && (
        <p role="alert" className="m-0 self-start rounded-lg bg-stop-tint px-3 py-1.5 text-sm text-stop">
          {serverError}
        </p>
      )}
    </div>
  );
}

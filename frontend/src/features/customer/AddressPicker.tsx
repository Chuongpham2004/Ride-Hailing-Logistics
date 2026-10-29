import { useQuery } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { searchPlaces, type Place } from '@/shared/map/geocoder';
import { CloseIcon, LocateIcon } from '@/shared/ui/icons';
import type { StopKind } from './booking';

const MIN_CHARS = 3;
const DEBOUNCE_MS = 300;

function useDebounced(value: string, ms: number): string {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const id = setTimeout(() => setDebounced(value), ms);
    return () => clearTimeout(id);
  }, [value, ms]);
  return debounced;
}

export interface AddressPickerProps {
  kind: StopKind;
  near: { latitude: number; longitude: number } | null;
  onPlace: (place: Place) => void;
  onUseLocation?: () => void;
  locating?: boolean;
  locationError?: string | null;
  onClose: () => void;
}

/**
 * Search for a pickup or drop-off. Suggestions come from Photon after a pause in typing (cached
 * per text, older requests cancelled); a pin dropped on the map works as well.
 */
export function AddressPicker({
  kind,
  near,
  onPlace,
  onUseLocation,
  locating,
  locationError,
  onClose,
}: AddressPickerProps) {
  const [text, setText] = useState('');
  const query = useDebounced(text.trim(), DEBOUNCE_MS);
  const enabled = query.length >= MIN_CHARS;
  const nearKey = near ? `${near.latitude.toFixed(2)},${near.longitude.toFixed(2)}` : '';

  const places = useQuery({
    queryKey: ['places', query, nearKey],
    queryFn: ({ signal }) => searchPlaces(query, near, signal),
    enabled,
    staleTime: Infinity,
    retry: 1,
  });

  const title = kind === 'pickup' ? 'Chọn điểm đón' : 'Chọn điểm đến';

  return (
    <div className="flex min-h-0 flex-col gap-3">
      <div className="flex items-center justify-between gap-3">
        <h2 className="m-0 text-xl font-bold">{title}</h2>
        <button
          type="button"
          onClick={onClose}
          aria-label="Đóng"
          className="flex size-11 items-center justify-center rounded-full text-ink-2 hover:bg-concrete-2"
        >
          <CloseIcon />
        </button>
      </div>

      <label className="flex flex-col gap-1 text-sm text-curb">
        Địa chỉ hoặc tên địa điểm
        <input
          type="search"
          autoFocus
          value={text}
          onChange={(event) => setText(event.target.value)}
          placeholder={kind === 'pickup' ? 'Ví dụ: Chợ Bến Thành' : 'Ví dụ: ĐH Khoa học Tự nhiên'}
          className="h-12 rounded-[10px] border-[1.5px] border-field px-3.5 text-base text-asphalt"
        />
      </label>

      {onUseLocation && (
        <button
          type="button"
          onClick={onUseLocation}
          disabled={locating}
          className="flex min-h-11 items-center gap-2.5 self-start rounded-[10px] px-1 font-semibold text-guide disabled:opacity-60"
        >
          <LocateIcon />
          {locating ? 'Đang lấy vị trí…' : 'Dùng vị trí hiện tại'}
        </button>
      )}
      {locationError && <p className="m-0 text-sm text-stop">{locationError}</p>}

      <div aria-live="polite" className="min-h-0 overflow-y-auto">
        {!enabled && (
          <p className="m-0 text-sm text-curb">Gõ ít nhất 3 ký tự, hoặc chạm vào bản đồ để đặt ghim.</p>
        )}
        {enabled && places.isPending && <p className="m-0 text-sm text-curb">Đang tìm…</p>}
        {enabled && places.isError && (
          <p className="m-0 text-sm text-stop">
            Không tìm được địa chỉ lúc này. Chạm vào bản đồ để đặt ghim.
          </p>
        )}
        {places.data && places.data.length === 0 && (
          <p className="m-0 text-sm text-curb">
            Không thấy địa điểm nào khớp. Thử tên khác, hoặc đặt ghim trên bản đồ.
          </p>
        )}
        {places.data && places.data.length > 0 && (
          <ul className="m-0 flex list-none flex-col p-0">
            {places.data.map((place) => (
              <li
                key={`${place.latitude},${place.longitude},${place.label}`}
                className="border-t border-dashed border-line"
              >
                <button
                  type="button"
                  onClick={() => onPlace(place)}
                  className="w-full py-3 text-left text-[15px] text-asphalt hover:bg-concrete-2"
                >
                  {place.label}
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}

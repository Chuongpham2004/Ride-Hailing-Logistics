import { useState } from 'react';
import { useNavigate } from 'react-router';
import { ApiError } from '@/shared/api/ApiError';
import { messageFor } from '@/shared/api/messages';
import type { ServiceType, Stop } from '@/shared/api/types';
import { useHeight } from '@/shared/lib/useHeight';
import { MapView, type MapPoint } from '@/shared/map/MapView';
import { CITY_CENTER, pinLabel, reverseGeocode, type Place } from '@/shared/map/geocoder';
import { Button } from '@/shared/ui/Button';
import { ParcelIcon, RideIcon } from '@/shared/ui/icons';
import { Sheet } from '@/shared/ui/Sheet';
import { AddressPicker } from './AddressPicker';
import { SERVICE_NAMES, toStop, useBooking, useCreateQuote, type StopKind } from './booking';

const SERVICES: { value: ServiceType; Icon: typeof RideIcon }[] = [
  { value: 'RIDE', Icon: RideIcon },
  { value: 'DELIVERY', Icon: ParcelIcon },
];

function quoteError(error: unknown): string {
  if (error instanceof ApiError && error.code === 'BUSINESS_RULE_VIOLATION') {
    return 'Chưa báo giá được cho hai điểm này: chúng quá gần hoặc quá xa nhau. Chọn lại điểm đón hoặc điểm đến.';
  }
  return messageFor(error);
}

function geolocationError(error: GeolocationPositionError): string {
  if (error.code === error.PERMISSION_DENIED) {
    return 'Trang chưa được phép dùng vị trí. Bật quyền vị trí trong trình duyệt, hoặc tìm địa chỉ.';
  }
  return 'Chưa lấy được vị trí. Thử lại, hoặc tìm địa chỉ.';
}

/** /app: map plus a sheet to pick the service, the pickup and the drop-off, then ask for a price. */
export default function BookingPage() {
  const navigate = useNavigate();
  const { service, pickup, dropoff, setService, setStop } = useBooking();
  const createQuote = useCreateQuote();
  const [editing, setEditing] = useState<StopKind | null>(null);
  const [locating, setLocating] = useState(false);
  const [locationError, setLocationError] = useState<string | null>(null);
  const [sheetRef, sheetHeight] = useHeight<HTMLDivElement>();

  /** Sets the stop at once, then swaps the coordinates label for an address when one is found. */
  const placeStop = (kind: StopKind, point: MapPoint, label?: string) => {
    const stop = toStop(point.latitude, point.longitude, label ?? pinLabel(point.latitude, point.longitude));
    setStop(kind, stop);
    setEditing(null);
    createQuote.reset();
    if (label) return;
    reverseGeocode(point.latitude, point.longitude)
      .then((address) => {
        const current = useBooking.getState()[kind];
        if (
          address &&
          current &&
          current.latitude === stop.latitude &&
          current.longitude === stop.longitude
        ) {
          setStop(kind, toStop(stop.latitude, stop.longitude, address));
        }
      })
      .catch(() => {
        // Keep the coordinates label.
      });
  };

  const locateMe = () => {
    if (!('geolocation' in navigator)) {
      setLocationError('Trình duyệt này không hỗ trợ lấy vị trí. Tìm địa chỉ thay thế.');
      return;
    }
    setLocating(true);
    setLocationError(null);
    navigator.geolocation.getCurrentPosition(
      (position) => {
        setLocating(false);
        placeStop('pickup', { latitude: position.coords.latitude, longitude: position.coords.longitude });
      },
      (error) => {
        setLocating(false);
        setLocationError(geolocationError(error));
      },
      { enableHighAccuracy: true, timeout: 10_000 },
    );
  };

  const askForPrice = () => {
    if (!pickup || !dropoff) return;
    createQuote.mutate(
      { serviceType: service, pickup, dropoff },
      { onSuccess: (quote) => navigate(`/app/quote/${quote.id}`) },
    );
  };

  return (
    <div className="absolute inset-0">
      <MapView
        className="absolute inset-0"
        pickup={pickup}
        dropoff={dropoff}
        bottomInset={sheetHeight}
        onPick={editing ? (point) => placeStop(editing, point) : undefined}
      />

      <div ref={sheetRef} className="absolute inset-x-0 bottom-0 max-h-[75%] overflow-y-auto">
        <Sheet>
          {editing ? (
            <AddressPicker
              kind={editing}
              near={pickup ?? dropoff ?? CITY_CENTER}
              onPlace={(place: Place) => placeStop(editing, place, place.label)}
              onUseLocation={editing === 'pickup' ? locateMe : undefined}
              locating={locating}
              locationError={locationError}
              onClose={() => setEditing(null)}
            />
          ) : (
            <>
              <div
                role="radiogroup"
                aria-label="Dịch vụ"
                className="grid grid-cols-2 gap-1 rounded-[14px] bg-concrete-2 p-1"
              >
                {SERVICES.map(({ value, Icon }) => {
                  const checked = service === value;
                  return (
                    <button
                      key={value}
                      type="button"
                      role="radio"
                      aria-checked={checked}
                      onClick={() => {
                        setService(value);
                        createQuote.reset();
                      }}
                      className={`flex h-11.5 items-center justify-center gap-2 rounded-[10px] text-[15px] ${
                        checked
                          ? 'bg-white font-bold text-asphalt shadow-[0_1px_3px_rgba(43,46,51,0.16)]'
                          : 'font-medium text-ink-2'
                      }`}
                    >
                      <Icon />
                      {SERVICE_NAMES[value]}
                    </button>
                  );
                })}
              </div>

              <div className="relative flex flex-col">
                <div
                  aria-hidden="true"
                  className="absolute top-[34px] bottom-[34px] left-[11px] border-l-2 border-dashed border-[#B9BDB6]"
                />
                <StopButton kind="pickup" stop={pickup} onClick={() => setEditing('pickup')} />
                <StopButton kind="dropoff" stop={dropoff} onClick={() => setEditing('dropoff')} />
              </div>

              {createQuote.isError && (
                <p role="alert" className="m-0 rounded-[10px] bg-stop-tint px-3.5 py-3 text-[15px]">
                  {quoteError(createQuote.error)}
                </p>
              )}

              <Button onClick={askForPrice} disabled={!pickup || !dropoff} busy={createQuote.isPending}>
                Xem giá
              </Button>
            </>
          )}
        </Sheet>
      </div>
    </div>
  );
}

function StopButton({ kind, stop, onClick }: { kind: StopKind; stop: Stop | null; onClick: () => void }) {
  const label = kind === 'pickup' ? 'Điểm đón' : 'Điểm đến';
  const empty = kind === 'pickup' ? 'Chọn nơi đón' : 'Chọn nơi đến';
  return (
    <button
      type="button"
      onClick={onClick}
      className="flex items-center gap-3.5 py-2.5 text-left text-asphalt"
    >
      {kind === 'pickup' ? (
        <span
          aria-hidden="true"
          className="flex size-6 flex-none items-center justify-center rounded-full bg-asphalt"
        >
          <span className="size-3.5 rounded-full bg-sign" />
        </span>
      ) : (
        <span aria-hidden="true" className="flex size-6 flex-none items-center justify-center">
          <span className="flex size-4.5 items-center justify-center rounded-[2px] bg-asphalt">
            <span className="size-1.75 bg-white" />
          </span>
        </span>
      )}
      <span className="flex min-w-0 flex-col">
        <span className="text-[13px] text-curb">{label}</span>
        <span className={`truncate text-[17px] font-semibold ${stop ? '' : 'text-curb'}`}>
          {stop?.address ?? empty}
        </span>
      </span>
    </button>
  );
}

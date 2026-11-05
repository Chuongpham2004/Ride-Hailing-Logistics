import { useState } from 'react';
import { ApiError } from '@/shared/api/ApiError';
import { messageFor } from '@/shared/api/messages';
import type { DriverDocument, DriverProfile, Vehicle } from '@/shared/api/types';
import { formatDate } from '@/shared/lib/format';
import { Button } from '@/shared/ui/Button';
import {
  DOCUMENT_NAMES,
  DRIVER_DOCUMENTS,
  FIELD_CLASS,
  MAX_UPLOAD_BYTES,
  UPLOAD_TYPES,
  useSubmitDocument,
  todayIso,
  VEHICLE_DOCUMENTS,
  type DocumentType,
} from './onboarding';

interface Slot {
  key: string;
  type: DocumentType;
  vehicle?: Vehicle;
  current?: DriverDocument;
}

/** Step 3: the papers the server requires, for the driver and for every active vehicle. */
export function DocumentsStep({ profile }: { profile: DriverProfile }) {
  const [editing, setEditing] = useState<string | null>(null);
  const vehicles = profile.vehicles.filter((v) => v.status === 'ACTIVE');
  const active = profile.documents.filter((d) => d.status === 'ACTIVE');
  const find = (type: DocumentType, vehicleId?: string) =>
    active.find((d) => d.type === type && (d.vehicleId ?? undefined) === vehicleId);

  const slots: Slot[] = [
    ...DRIVER_DOCUMENTS.map((type) => ({ key: type, type, current: find(type) })),
    ...vehicles.flatMap((vehicle) =>
      VEHICLE_DOCUMENTS.map((type) => ({
        key: `${type}:${vehicle.id}`,
        type,
        vehicle,
        current: find(type, vehicle.id),
      })),
    ),
  ];

  return (
    <div className="flex flex-col gap-2.5">
      {vehicles.length === 0 && (
        <p className="m-0 text-sm text-curb">Thêm xe ở bước 2 để nhập đăng ký và bảo hiểm của xe.</p>
      )}
      <ul className="m-0 flex list-none flex-col gap-2.5 p-0">
        {slots.map((slot) => (
          <li key={slot.key} className="rounded-[10px] border border-line">
            <SlotRow slot={slot} editing={editing === slot.key} onEdit={() => setEditing(slot.key)} />
            {editing === slot.key && <DocumentForm slot={slot} onClose={() => setEditing(null)} />}
          </li>
        ))}
      </ul>
    </div>
  );
}

function SlotRow({ slot, editing, onEdit }: { slot: Slot; editing: boolean; onEdit: () => void }) {
  const { current } = slot;
  const expired = current?.expiresOn !== undefined && current.expiresOn <= todayIso();
  const state = !current ? 'Còn thiếu' : expired ? 'Đã hết hạn' : 'Đã nhập';
  const tone = !current || expired ? 'text-stop' : 'text-go';
  return (
    <div className="flex items-center gap-3 px-3.5 py-2.5">
      <div className="flex min-w-0 flex-1 flex-col">
        <span className="font-semibold">
          {DOCUMENT_NAMES[slot.type]}
          {slot.vehicle && <span className="font-normal text-curb"> · {slot.vehicle.plateNumber}</span>}
        </span>
        <span className="text-sm">
          <span className={tone}>{state}</span>
          {current && (
            <span className="text-curb">
              {' '}
              · số {current.documentNumber}
              {current.expiresOn && ` · hết hạn ${formatDate(current.expiresOn)}`}
              {current.hasFile ? ' · có ảnh' : ' · chưa có ảnh'}
            </span>
          )}
        </span>
      </div>
      {!editing && (
        <button type="button" onClick={onEdit} className="min-h-11 rounded-lg px-2 text-sm font-semibold">
          {current ? 'Cập nhật' : 'Nhập'}
          <span className="sr-only"> {DOCUMENT_NAMES[slot.type]}</span>
        </button>
      )}
    </div>
  );
}

function DocumentForm({ slot, onClose }: { slot: Slot; onClose: () => void }) {
  const submit = useSubmitDocument();
  const [number, setNumber] = useState('');
  const [issuedOn, setIssuedOn] = useState('');
  const [expiresOn, setExpiresOn] = useState('');
  const [file, setFile] = useState<File | null>(null);
  const [problem, setProblem] = useState<string | null>(null);

  const onSubmit = (event: React.FormEvent) => {
    event.preventDefault();
    const documentNumber = number.trim().replace(/\s/g, '');
    if (!/^[A-Za-z0-9-]{1,40}$/.test(documentNumber)) {
      return setProblem('Số giấy tờ chỉ gồm chữ, số và dấu gạch ngang (tối đa 40 ký tự).');
    }
    if (issuedOn && expiresOn && expiresOn <= issuedOn) return setProblem('Ngày hết hạn phải sau ngày cấp.');
    if (expiresOn && expiresOn <= todayIso()) return setProblem('Giấy tờ này đã hết hạn.');
    if (file && file.size > MAX_UPLOAD_BYTES) return setProblem('Ảnh hoặc PDF tối đa 5 MB.');
    if (file && !/\.(jpe?g|png|pdf)$/i.test(file.name))
      return setProblem('Chỉ nhận ảnh JPG, PNG hoặc tệp PDF.');
    setProblem(null);
    submit.mutate(
      {
        type: slot.type,
        vehicleId: slot.vehicle?.id,
        documentNumber,
        issuedOn: issuedOn || undefined,
        expiresOn: expiresOn || undefined,
        file: file ?? undefined,
      },
      { onSuccess: onClose },
    );
  };

  const serverProblem = submit.isError ? documentErrorText(submit.error, file !== null) : null;

  return (
    <form
      onSubmit={onSubmit}
      noValidate
      className="flex flex-col gap-3 border-t border-dashed border-line px-3.5 pt-3 pb-3.5"
    >
      <label className="flex flex-col gap-1 text-sm text-curb">
        Số {DOCUMENT_NAMES[slot.type]}
        <input
          className={FIELD_CLASS}
          value={number}
          maxLength={40}
          onChange={(e) => setNumber(e.target.value)}
        />
      </label>
      <div className="grid grid-cols-2 gap-3">
        <label className="flex flex-col gap-1 text-sm text-curb">
          Ngày cấp
          <input
            type="date"
            className={FIELD_CLASS}
            value={issuedOn}
            onChange={(e) => setIssuedOn(e.target.value)}
          />
        </label>
        <label className="flex flex-col gap-1 text-sm text-curb">
          Ngày hết hạn
          <input
            type="date"
            className={FIELD_CLASS}
            value={expiresOn}
            onChange={(e) => setExpiresOn(e.target.value)}
          />
        </label>
      </div>
      <label className="flex flex-col gap-1 text-sm text-curb">
        Ảnh hoặc PDF (không bắt buộc, JPG / PNG / PDF, tối đa 5 MB)
        <input
          type="file"
          accept={UPLOAD_TYPES}
          onChange={(e) => setFile(e.target.files?.[0] ?? null)}
          className="text-[15px] text-asphalt file:mr-3 file:rounded-lg file:border-0 file:bg-concrete-2 file:px-3 file:py-2 file:font-semibold"
        />
      </label>
      {(problem || serverProblem) && (
        <p role="alert" className="m-0 text-sm text-stop">
          {problem ?? serverProblem}
        </p>
      )}
      <Button type="submit" busy={submit.isPending}>
        Lưu giấy tờ
      </Button>
      <Button variant="secondary" onClick={onClose} disabled={submit.isPending}>
        Huỷ
      </Button>
    </form>
  );
}

function documentErrorText(error: unknown, withFile: boolean): string {
  if (error instanceof ApiError) {
    if (error.code === 'RATE_LIMIT_EXCEEDED')
      return 'Bạn đã tải lên quá nhiều tệp trong một giờ, thử lại sau.';
    if (error.code === 'BUSINESS_RULE_VIOLATION' && withFile) {
      return 'Tệp không dùng được: chỉ nhận ảnh JPG / PNG hoặc PDF thường, tối đa 5 MB, đúng đuôi tệp.';
    }
    if (error.code === 'BUSINESS_RULE_VIOLATION')
      return 'Thông tin giấy tờ chưa hợp lệ, kiểm tra lại ngày cấp và ngày hết hạn.';
  }
  return messageFor(error);
}

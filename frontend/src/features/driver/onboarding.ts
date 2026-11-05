import { useMutation, useQueryClient } from '@tanstack/react-query';
import { api } from '@/shared/api/client';
import type {
  DriverDocument,
  DriverProfile,
  ReviewStatus,
  ServiceType,
  Vehicle,
  VehicleType,
} from '@/shared/api/types';
import { refreshUser } from '@/shared/auth/session';
import { profileKey } from './profile';

/*
 * Driver onboarding (UC-01, frontend-design.md §4.3): profile → vehicle → papers → a verified
 * contact → submit for review. Every change reloads the profile, whose missingRequirements says
 * what is still blocking the submission.
 */

export type DocumentType = DriverDocument['type'];

export const DOCUMENT_NAMES: Record<DocumentType, string> = {
  NATIONAL_ID: 'CCCD / CMND',
  DRIVER_LICENSE: 'Giấy phép lái xe',
  VEHICLE_REGISTRATION: 'Đăng ký xe (cà vẹt)',
  VEHICLE_INSURANCE: 'Bảo hiểm xe',
};

/** As configured in user-service (rhl.documents.required-*). */
export const DRIVER_DOCUMENTS: DocumentType[] = ['NATIONAL_ID', 'DRIVER_LICENSE'];
export const VEHICLE_DOCUMENTS: DocumentType[] = ['VEHICLE_REGISTRATION', 'VEHICLE_INSURANCE'];

/** The profile can only be changed in these states (user-service ReviewStatus.isEditable). */
export const EDITABLE: ReadonlySet<ReviewStatus> = new Set(['DRAFT', 'REJECTED']);

/** Accepted by the server (UploadInspector): JPEG, PNG or PDF up to 5 MB. */
export const UPLOAD_TYPES = '.jpg,.jpeg,.png,.pdf,image/jpeg,image/png,application/pdf';
export const MAX_UPLOAD_BYTES = 5 * 1024 * 1024;

/** Text inputs and selects on the onboarding forms. */
export const FIELD_CLASS =
  'h-12 rounded-[10px] border-[1.5px] border-field bg-white px-3.5 text-base text-asphalt aria-invalid:border-stop';

/** Vietnamese plate after removing spaces, dots and dashes (user-service Vehicle.PLATE). */
export const PLATE = /^[0-9]{2}[A-Z]{1,2}[0-9]?[0-9]{4,5}$/;
export const normalisePlate = (raw: string) => raw.toUpperCase().replace(/[\s.-]/g, '');

/**
 * The server's missingRequirements are English sentences; turn the known shapes into Vietnamese
 * and keep anything else as sent.
 */
export function requirementText(text: string): string {
  if (text === 'Verify your email or phone number first') return 'Xác minh email hoặc số điện thoại';
  if (text === 'At least one active vehicle is required') return 'Thêm ít nhất một xe';
  const vehicle = /^(VEHICLE_\w+) of (\S+) (is missing|has expired)$/.exec(text);
  if (vehicle) {
    const name = DOCUMENT_NAMES[vehicle[1] as DocumentType] ?? vehicle[1];
    return `${name} của xe ${vehicle[2]} ${vehicle[3] === 'is missing' ? 'còn thiếu' : 'đã hết hạn'}`;
  }
  const own = /^(\w+) (is missing|has expired)$/.exec(text);
  if (own && own[1]! in DOCUMENT_NAMES) {
    return `${DOCUMENT_NAMES[own[1] as DocumentType]} ${own[2] === 'is missing' ? 'còn thiếu' : 'đã hết hạn'}`;
  }
  return text;
}

function useReload() {
  const queryClient = useQueryClient();
  return () => queryClient.invalidateQueries({ queryKey: profileKey });
}

export interface ProfileInput {
  fullName: string;
  dateOfBirth?: string;
  serviceTypes: ServiceType[];
}

/** POST the first time, PUT afterwards. */
export function useSaveProfile(exists: boolean) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: ProfileInput) =>
      api<DriverProfile>('/api/v1/drivers/me/profile', { method: exists ? 'PUT' : 'POST', body: input }),
    onSuccess: (profile) => queryClient.setQueryData(profileKey, profile),
  });
}

export interface VehicleInput {
  type: VehicleType;
  plateNumber: string;
  brand: string;
  model: string;
  color: string;
  manufactureYear: number;
}

export function useAddVehicle() {
  const reload = useReload();
  return useMutation({
    mutationFn: (input: VehicleInput) =>
      api<Vehicle>('/api/v1/drivers/me/vehicles', { method: 'POST', body: input }),
    onSuccess: () => reload(),
  });
}

export function useRemoveVehicle() {
  const reload = useReload();
  return useMutation({
    mutationFn: (vehicleId: string) =>
      api<void>(`/api/v1/drivers/me/vehicles/${encodeURIComponent(vehicleId)}`, { method: 'DELETE' }),
    onSuccess: () => reload(),
  });
}

export interface DocumentInput {
  type: DocumentType;
  vehicleId?: string;
  documentNumber: string;
  issuedOn?: string;
  expiresOn?: string;
  /** Optional scan or photo, uploaded first. */
  file?: File;
}

/** Uploads the file (if any), then records the document; a document of the same type replaces the old one. */
export function useSubmitDocument() {
  const reload = useReload();
  return useMutation({
    mutationFn: async ({ file, ...input }: DocumentInput) => {
      let fileId: string | undefined;
      if (file) {
        const form = new FormData();
        form.append('file', file);
        fileId = (
          await api<{ fileId: string }>('/api/v1/drivers/me/documents/files', { method: 'POST', body: form })
        ).fileId;
      }
      return api<DriverDocument>('/api/v1/drivers/me/documents', {
        method: 'POST',
        body: { ...input, fileId },
      });
    },
    onSuccess: () => reload(),
  });
}

export function useSubmitForReview() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => api<DriverProfile>('/api/v1/drivers/me/profile/submit', { method: 'POST' }),
    onSuccess: (profile) => queryClient.setQueryData(profileKey, profile),
    onError: () => void queryClient.invalidateQueries({ queryKey: profileKey }),
  });
}

export type Channel = 'email' | 'phone';

export interface CodeSent {
  channel: string;
  destination: string;
  expiresInSeconds: number;
  resendAfterSeconds: number;
}

export function useSendCode() {
  return useMutation({
    mutationFn: (channel: Channel) =>
      api<CodeSent>(`/api/v1/users/me/contacts/${channel}/verification`, { method: 'POST' }),
  });
}

export function useConfirmCode() {
  const reload = useReload();
  return useMutation({
    mutationFn: ({ channel, code }: { channel: Channel; code: string }) =>
      api(`/api/v1/users/me/contacts/${channel}/verification/confirm`, { method: 'POST', body: { code } }),
    onSuccess: async () => {
      await refreshUser();
      await reload();
    },
  });
}

/** "yyyy-mm-dd" of today in local time, to compare with the server's LocalDate fields. */
export function todayIso(now = new Date()): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
}

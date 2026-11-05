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
import type { DocumentType } from '@/shared/drivers/text';
import { profileKey } from './profile';

/*
 * Driver onboarding (UC-01, frontend-design.md §4.3): profile → vehicle → papers → a verified
 * contact → submit for review. Every change reloads the profile, whose missingRequirements says
 * what is still blocking the submission.
 */

export type { DocumentType } from '@/shared/drivers/text';
export { DOCUMENT_NAMES, requirementText } from '@/shared/drivers/text';

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

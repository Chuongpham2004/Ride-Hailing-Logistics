import type { DriverDocument, ReviewStatus, ServiceType, VehicleType } from '@/shared/api/types';

/* How driver profiles are named on screen, in the driver area and in the staff area alike. */

export type DocumentType = DriverDocument['type'];

export const DOCUMENT_NAMES: Record<DocumentType, string> = {
  NATIONAL_ID: 'CCCD / CMND',
  DRIVER_LICENSE: 'Giấy phép lái xe',
  VEHICLE_REGISTRATION: 'Đăng ký xe (cà vẹt)',
  VEHICLE_INSURANCE: 'Bảo hiểm xe',
};

export const VEHICLE_NAMES: Record<VehicleType, string> = {
  MOTORBIKE: 'Xe máy',
  CAR_4_SEAT: 'Ô tô 4 chỗ',
  CAR_7_SEAT: 'Ô tô 7 chỗ',
  VAN: 'Xe tải van',
};

export const SERVICE_NAMES: Record<ServiceType, string> = {
  RIDE: 'Chở khách',
  DELIVERY: 'Giao hàng',
};

export const REVIEW_STATUS_NAMES: Record<ReviewStatus, string> = {
  DRAFT: 'Đang soạn',
  PENDING_REVIEW: 'Chờ duyệt',
  APPROVED: 'Đã duyệt',
  REJECTED: 'Bị từ chối',
  SUSPENDED: 'Tạm khoá',
};

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

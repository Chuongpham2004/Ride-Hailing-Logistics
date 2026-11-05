import type {
  ActorType,
  CancelReason,
  PaymentPurpose,
  PaymentStatus,
  RefundReason,
} from '@/shared/api/types';

/* How trips and payments are named on screen, shared by the trip, receipt and activity screens. */

export const REASON_NAMES: Record<CancelReason, string> = {
  CHANGED_MIND: 'đổi ý',
  WAIT_TOO_LONG: 'chờ quá lâu',
  DRIVER_NOT_MOVING: 'tài xế không di chuyển',
  CUSTOMER_NO_SHOW: 'khách không ra điểm đón',
  WRONG_PICKUP: 'sai điểm đón',
  VEHICLE_ISSUE: 'xe gặp sự cố',
  SAFETY_CONCERN: 'lo ngại an toàn',
  OTHER: 'lý do khác',
};

export const CANCELLED_BY: Record<ActorType, string> = {
  CUSTOMER: 'Bạn đã huỷ chuyến',
  DRIVER: 'Tài xế đã huỷ chuyến',
  STAFF: 'Tổng đài đã huỷ chuyến',
  SYSTEM: 'Chuyến đã bị huỷ',
};

export const ACTOR_NAMES: Record<ActorType, string> = {
  CUSTOMER: 'bạn',
  DRIVER: 'tài xế',
  STAFF: 'tổng đài',
  SYSTEM: 'hệ thống',
};

export const PAYMENT_STATUS_NAMES: Record<PaymentStatus, string> = {
  PENDING: 'Đang xử lý',
  SUCCEEDED: 'Đã thanh toán',
  FAILED: 'Chưa thanh toán được',
  REFUND_PENDING: 'Đang hoàn tiền',
  PARTIALLY_REFUNDED: 'Đã hoàn một phần',
  REFUNDED: 'Đã hoàn tiền',
};

export const PURPOSE_NAMES: Record<PaymentPurpose, string> = {
  TRIP_FARE: 'Cước chuyến',
  CANCELLATION_FEE: 'Phí huỷ chuyến',
};

export const REFUND_REASON_NAMES: Record<RefundReason, string> = {
  SERVICE_NOT_PROVIDED: 'Chuyến không diễn ra như đã đặt',
  OVERCHARGE: 'Thu dư',
  DUPLICATE_CHARGE: 'Thu trùng',
  SERVICE_COMPLAINT: 'Giải quyết khiếu nại',
  GOODWILL: 'Hỗ trợ khách hàng',
  OTHER: 'Lý do khác',
};

export { formatDateTime, formatTime } from '@/shared/lib/format';
export { STATUS_NAMES } from '@/shared/trips/text';

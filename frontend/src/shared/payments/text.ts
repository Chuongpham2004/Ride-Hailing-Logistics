import type { PaymentPurpose, PaymentStatus, RefundReason, WalletEntry } from '@/shared/api/types';

/* How payments, refunds and wallet lines are named on screen, in every area. */

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

export const ENTRY_NAMES: Record<WalletEntry['type'], string> = {
  EARNING: 'Tiền chuyến',
  COMMISSION: 'Phí nền tảng',
  ADJUSTMENT: 'Điều chỉnh',
};

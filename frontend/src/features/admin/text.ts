import type {
  ActorType,
  AdjustmentReason,
  CancelReason,
  ReviewDecisionType,
  StaffOffer,
  UserStatus,
} from '@/shared/api/types';

/* Words for the staff area; staff read about customers and drivers in the third person. */

export const DECISION_NAMES: Record<ReviewDecisionType, string> = {
  APPROVED: 'Duyệt',
  REJECTED: 'Từ chối',
  CHANGES_REQUESTED: 'Yêu cầu bổ sung',
  SUSPENDED: 'Tạm khoá',
  REINSTATED: 'Mở lại',
};

export const ACTOR_NAMES: Record<ActorType, string> = {
  CUSTOMER: 'khách',
  DRIVER: 'tài xế',
  STAFF: 'nhân viên',
  SYSTEM: 'hệ thống',
};

export const OFFER_STATUS_NAMES: Record<StaffOffer['status'], string> = {
  PENDING: 'Đang chờ',
  ACCEPTED: 'Đã nhận',
  DECLINED: 'Từ chối',
  EXPIRED: 'Hết hạn',
  CANCELLED: 'Đã huỷ',
};

export const CANCEL_REASON_NAMES: Record<CancelReason, string> = {
  CHANGED_MIND: 'Khách đổi ý',
  WAIT_TOO_LONG: 'Chờ quá lâu',
  DRIVER_NOT_MOVING: 'Tài xế không di chuyển',
  CUSTOMER_NO_SHOW: 'Khách không ra điểm đón',
  WRONG_PICKUP: 'Sai điểm đón',
  VEHICLE_ISSUE: 'Xe gặp sự cố',
  SAFETY_CONCERN: 'Lo ngại an toàn',
  OTHER: 'Lý do khác',
};

/**
 * The last 8 characters of an id. The ids are UUIDv7, whose first characters are a timestamp and
 * look the same for rows created close together; the full id is on hover and on the detail page.
 */
export function shortId(id: string): string {
  return `…${id.slice(-8)}`;
}

/** A status change's reason is a CancelReason code for cancellations; anything else is shown as sent. */
export function changeReason(reason: string): string {
  return CANCEL_REASON_NAMES[reason as CancelReason] ?? reason;
}

export const ADJUSTMENT_REASON_NAMES: Record<AdjustmentReason, string> = {
  REFUND_CLAWBACK: 'Thu lại phần đã hoàn cho khách',
  EARNING_CORRECTION: 'Sửa khoản thu nhập / phí sai',
  INCENTIVE: 'Thưởng',
  OTHER: 'Lý do khác',
};

export const USER_STATUS_NAMES: Record<UserStatus, string> = {
  ACTIVE: 'Đang hoạt động',
  LOCKED: 'Tạm khoá',
  DISABLED: 'Vô hiệu hoá',
};

/** Inputs and selects in the staff area. */
export const FIELD = 'h-10 rounded-lg border-[1.5px] border-field bg-white px-3 text-[15px] text-asphalt';

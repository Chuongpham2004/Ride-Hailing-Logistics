import type { TripStatus } from '@/shared/api/types';

/** Short trip status for lists and timelines, the same words in every area. */
export const STATUS_NAMES: Record<TripStatus, string> = {
  CREATED: 'Đã đặt',
  MATCHING: 'Đang tìm tài xế',
  ACCEPTED: 'Tài xế đã nhận',
  PICKING_UP: 'Tài xế đang đến',
  ARRIVED: 'Tài xế đã đến',
  IN_TRIP: 'Đang chở',
  COMPLETED: 'Hoàn tất',
  CANCELLED: 'Đã huỷ',
  NO_DRIVER: 'Không có tài xế',
};

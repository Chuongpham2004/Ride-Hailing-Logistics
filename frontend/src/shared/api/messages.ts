import { ApiError } from './ApiError';

/** What the UI says for each server error code (frontend-design.md §7); never the server's message. */
const MESSAGES: Record<string, string> = {
  VALIDATION_ERROR: 'Một vài thông tin chưa hợp lệ, kiểm tra lại các ô được đánh dấu.',
  AUTHENTICATION_REQUIRED: 'Phiên đăng nhập đã hết, đăng nhập lại để tiếp tục.',
  INVALID_CREDENTIALS: 'Email, số điện thoại hoặc mật khẩu chưa đúng.',
  ACCESS_DENIED: 'Không tìm thấy nội dung này.',
  RESOURCE_NOT_FOUND: 'Không tìm thấy nội dung này.',
  QUOTE_EXPIRED: 'Báo giá đã hết hạn. Lấy giá mới để đặt chuyến.',
  INVALID_TRIP_STATE: 'Chuyến vừa chuyển sang bước khác, xem lại thông tin mới nhất.',
  BUSINESS_RULE_VIOLATION: 'Không thực hiện được yêu cầu này với thông tin hiện tại.',
  RATE_LIMIT_EXCEEDED: 'Thao tác quá nhanh, thử lại sau ít giây.',
  DEPENDENCY_UNAVAILABLE: 'Hệ thống đang bận, thử lại sau ít phút.',
  NETWORK_ERROR: 'Không kết nối được máy chủ. Kiểm tra mạng rồi thử lại.',
};

const FALLBACK = 'Có lỗi xảy ra, thử lại sau ít phút.';

export function messageFor(error: unknown): string {
  if (error instanceof ApiError) return MESSAGES[error.code] ?? FALLBACK;
  return FALLBACK;
}

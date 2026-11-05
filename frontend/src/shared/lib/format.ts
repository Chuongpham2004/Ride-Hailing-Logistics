const vnd = new Intl.NumberFormat('vi-VN', { style: 'currency', currency: 'VND', maximumFractionDigits: 0 });
const oneDecimal = new Intl.NumberFormat('vi-VN', { maximumFractionDigits: 1 });

/** 29000 → "29.000 ₫". Amounts are whole VND from the server; the client never computes a price. */
export function formatVnd(amount: number): string {
  return vnd.format(amount);
}

/** 850 → "850 m", 3140 → "3,1 km". */
export function formatDistance(meters: number): string {
  if (meters < 1000) return `${Math.round(meters)} m`;
  return `${oneDecimal.format(meters / 1000)} km`;
}

/** 540 → "9 phút", 3900 → "1 giờ 5 phút"; never below one minute. */
export function formatDuration(seconds: number): string {
  const minutes = Math.max(1, Math.round(seconds / 60));
  if (minutes < 60) return `${minutes} phút`;
  const hours = Math.floor(minutes / 60);
  const rest = minutes % 60;
  return rest === 0 ? `${hours} giờ` : `${hours} giờ ${rest} phút`;
}

/** 292 → "4:52". */
export function formatCountdown(seconds: number): string {
  const s = Math.max(0, Math.floor(seconds));
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
}

const dateTime = new Intl.DateTimeFormat('vi-VN', {
  hour: '2-digit',
  minute: '2-digit',
  day: '2-digit',
  month: '2-digit',
  year: 'numeric',
});
const time = new Intl.DateTimeFormat('vi-VN', { hour: '2-digit', minute: '2-digit', second: '2-digit' });

/** "14:05 30/10/2026". */
export function formatDateTime(iso: string): string {
  return dateTime.format(new Date(iso));
}

/** "14:05:12". */
export function formatTime(iso: string): string {
  return time.format(new Date(iso));
}

const date = new Intl.DateTimeFormat('vi-VN', { day: '2-digit', month: '2-digit', year: 'numeric' });

/** "2026-11-05" (a LocalDate from the server) → "05/11/2026"; parsed as a calendar date, not UTC midnight. */
export function formatDate(isoDate: string): string {
  const [y, m, d] = isoDate.split('-').map(Number);
  return date.format(new Date(y!, (m ?? 1) - 1, d ?? 1));
}

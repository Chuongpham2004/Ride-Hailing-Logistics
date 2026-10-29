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

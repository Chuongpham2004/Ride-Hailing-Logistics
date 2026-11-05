import type { PaymentStatus, ReviewStatus, TripStatus } from '@/shared/api/types';
import { REVIEW_STATUS_NAMES } from '@/shared/drivers/text';
import { PAYMENT_STATUS_NAMES } from '@/shared/payments/text';
import { STATUS_NAMES } from '@/shared/trips/text';

/*
 * The one place that turns a status into a label and a colour (frontend-design.md §9): amber =
 * waiting or in progress, blue = on the road, green = done, red = cancelled or blocked,
 * grey = nothing happened. The label always says it in words too.
 */

type Tone = 'amber' | 'blue' | 'green' | 'red' | 'grey';

const TONES: Record<Tone, string> = {
  amber: 'bg-sign/25 text-asphalt',
  blue: 'bg-guide/10 text-guide',
  green: 'bg-go/10 text-go',
  red: 'bg-stop-tint text-stop',
  grey: 'bg-concrete-2 text-curb',
};

const TRIP_TONES: Record<TripStatus, Tone> = {
  CREATED: 'amber',
  MATCHING: 'amber',
  ACCEPTED: 'blue',
  PICKING_UP: 'blue',
  ARRIVED: 'blue',
  IN_TRIP: 'blue',
  COMPLETED: 'green',
  CANCELLED: 'red',
  NO_DRIVER: 'grey',
};

const REVIEW_TONES: Record<ReviewStatus, Tone> = {
  DRAFT: 'grey',
  PENDING_REVIEW: 'amber',
  APPROVED: 'green',
  REJECTED: 'red',
  SUSPENDED: 'red',
};

const PAYMENT_TONES: Record<PaymentStatus, Tone> = {
  PENDING: 'amber',
  SUCCEEDED: 'green',
  FAILED: 'red',
  REFUND_PENDING: 'amber',
  PARTIALLY_REFUNDED: 'blue',
  REFUNDED: 'grey',
};

export function Badge({ tone, children }: { tone: Tone; children: string }) {
  return (
    <span
      className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-0.5 text-sm font-semibold whitespace-nowrap ${TONES[tone]}`}
    >
      <span aria-hidden="true" className="size-1.5 rounded-full bg-current" />
      {children}
    </span>
  );
}

export function TripStatusBadge({ status }: { status: TripStatus }) {
  return <Badge tone={TRIP_TONES[status]}>{STATUS_NAMES[status]}</Badge>;
}

export function ReviewStatusBadge({ status }: { status: ReviewStatus }) {
  return <Badge tone={REVIEW_TONES[status]}>{REVIEW_STATUS_NAMES[status]}</Badge>;
}

export function PaymentStatusBadge({ status }: { status: PaymentStatus }) {
  return <Badge tone={PAYMENT_TONES[status]}>{PAYMENT_STATUS_NAMES[status]}</Badge>;
}

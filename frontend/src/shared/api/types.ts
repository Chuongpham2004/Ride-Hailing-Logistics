/*
 * Response shapes as the services send them. Fields the server leaves out when null
 * (default-property-inclusion: non_null) are optional here.
 */

export type Role = 'CUSTOMER' | 'DRIVER' | 'REVIEWER' | 'SUPPORT_STAFF' | 'FINANCE_STAFF' | 'ADMINISTRATOR';

export interface FieldError {
  field: string;
  reason: string;
}

/** Envelope around every REST response (README §8.2). */
export interface Envelope<T> {
  code: string;
  message: string;
  data?: T;
  correlationId?: string;
  details?: FieldError[];
}

/** user-service AuthController.TokenResponse; lifetimes in seconds. */
export interface TokenResponse {
  tokenType: 'Bearer';
  accessToken: string;
  expiresIn: number;
  refreshToken: string;
  refreshExpiresIn: number;
}

/** user-service AuthController.UserResponse. */
export interface CurrentUser {
  id: string;
  email?: string;
  phone?: string;
  fullName: string;
  roles: Role[];
  status: string;
  emailVerified: boolean;
  phoneVerified: boolean;
}

export type ServiceType = 'RIDE' | 'DELIVERY';

/** WGS84 point plus the address shown to people; the price depends only on the coordinates. */
export interface Stop {
  latitude: number;
  longitude: number;
  address: string;
}

/** pricing-service FareBreakdown; amounts in whole VND, components add up to total. */
export interface FareBreakdown {
  baseFare: number;
  distanceFare: number;
  timeFare: number;
  minimumFareAdjustment: number;
  surgeAmount: number;
  roundingAdjustment: number;
  total: number;
  surgeMultiplier: number;
}

/** pricing-service PricingViews.QuoteView. */
export interface Quote {
  id: string;
  customerId: string;
  serviceType: ServiceType;
  pickup: Stop;
  dropoff: Stop;
  distanceMeters: number;
  durationSeconds: number;
  /** OSRM for a road route, ESTIMATE for the straight-line fallback. */
  routeSource: string;
  /** Encoded polyline, precision 6; only when routeSource is OSRM. */
  routePolyline?: string;
  ruleId: string;
  ruleVersion: number;
  surgeMultiplier: number;
  surgeConfirmationRequired: boolean;
  surgeSource: string;
  surgeRuleVersion?: number;
  breakdown: FareBreakdown;
  total: number;
  currency: string;
  createdAt: string;
  expiresAt: string;
}

export type TripStatus =
  | 'CREATED'
  | 'MATCHING'
  | 'ACCEPTED'
  | 'PICKING_UP'
  | 'ARRIVED'
  | 'IN_TRIP'
  | 'COMPLETED'
  | 'CANCELLED'
  | 'NO_DRIVER';

export type CancelReason =
  | 'CHANGED_MIND'
  | 'WAIT_TOO_LONG'
  | 'DRIVER_NOT_MOVING'
  | 'CUSTOMER_NO_SHOW'
  | 'WRONG_PICKUP'
  | 'VEHICLE_ISSUE'
  | 'SAFETY_CONCERN'
  | 'OTHER';

export type ActorType = 'CUSTOMER' | 'DRIVER' | 'STAFF' | 'SYSTEM';

export type PackageSize = 'SMALL' | 'MEDIUM' | 'LARGE';

/** trip-service FareSnapshot: the price the trip was booked at. */
export interface TripFare {
  quoteId: string;
  quotedFare: number;
  currency: string;
  surgeMultiplier: number;
  pricingRuleVersion: number;
  distanceMeters: number;
  durationSeconds: number;
}

export interface DeliveryInput {
  recipientName: string;
  recipientPhone: string;
  packageDescription: string;
  packageSize: PackageSize;
  packageWeightGrams: number;
  instructions?: string;
}

/** trip-service TripViews.TripView; the codes are only sent to the trip's customer. */
export interface Trip {
  id: string;
  customerId: string;
  driverId?: string;
  serviceType: ServiceType;
  status: TripStatus;
  pickup: Stop;
  dropoff: Stop;
  fare?: TripFare;
  matchingDeadline?: string;
  cancelReason?: CancelReason;
  cancelledBy?: ActorType;
  createdAt: string;
  acceptedAt?: string;
  completedAt?: string;
  cancelledAt?: string;
  /** Also the aggregateVersion of the trip's events: newer events have a higher one. */
  version: number;
  delivery?: DeliveryInput;
  pickupCode?: string;
  deliveryCode?: string;
}

/** trip-service PricingClient.CancellationFee. */
export interface CancellationFee {
  decision: string;
  fee: number;
  currency: string;
  ruleVersion: number;
  freeUntil?: string;
}

/** trip-service TripViews.StatusChangeView. */
export interface StatusChange {
  fromStatus?: TripStatus;
  toStatus: TripStatus;
  actorType: ActorType;
  reason?: string;
  occurredAt: string;
}

/** trip-service TripViews.TripPage; pass nextBefore as `before` for the next page. */
export interface TripPage {
  items: Trip[];
  nextBefore?: string;
}

export type PaymentStatus =
  'PENDING' | 'SUCCEEDED' | 'FAILED' | 'REFUND_PENDING' | 'PARTIALLY_REFUNDED' | 'REFUNDED';

export type PaymentPurpose = 'TRIP_FARE' | 'CANCELLATION_FEE';

/** payment-service PaymentQueries.PaymentView; amounts in whole VND. */
export interface Payment {
  id: string;
  tripId: string;
  purpose: PaymentPurpose;
  customerId: string;
  driverId?: string;
  amount: number;
  refundedAmount: number;
  currency: string;
  status: PaymentStatus;
  attemptCount: number;
  provider?: string;
  providerRef?: string;
  failureCode?: string;
  createdAt: string;
  succeededAt?: string;
}

export type RefundReason =
  'SERVICE_NOT_PROVIDED' | 'OVERCHARGE' | 'DUPLICATE_CHARGE' | 'SERVICE_COMPLAINT' | 'GOODWILL' | 'OTHER';

/** payment-service PaymentQueries.RefundView as a customer sees it (no internal note). */
export interface Refund {
  id: string;
  paymentId: string;
  tripId: string;
  amount: number;
  currency: string;
  reason: RefundReason;
  status: 'PENDING' | 'SUCCEEDED' | 'FAILED';
  createdAt: string;
  completedAt?: string;
}

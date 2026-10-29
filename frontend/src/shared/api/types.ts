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

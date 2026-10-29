import type { SVGProps } from 'react';

/* Stroke icons drawn for the prototype; all decorative, so aria-hidden. */

function Icon({ children, ...props }: SVGProps<SVGSVGElement>) {
  return (
    <svg
      width={22}
      height={22}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={2}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      {...props}
    >
      {children}
    </svg>
  );
}

export const BackIcon = (p: SVGProps<SVGSVGElement>) => (
  <Icon strokeWidth={2.2} {...p}>
    <path d="M15 5l-7 7 7 7" />
  </Icon>
);

export const LocateIcon = (p: SVGProps<SVGSVGElement>) => (
  <Icon {...p}>
    <circle cx="12" cy="12" r="6" />
    <path d="M12 2v4M12 18v4M2 12h4M18 12h4" />
  </Icon>
);

export const ClockIcon = (p: SVGProps<SVGSVGElement>) => (
  <Icon width={16} height={16} strokeWidth={2.2} {...p}>
    <circle cx="12" cy="12" r="9" />
    <path d="M12 7v5l3 2" />
  </Icon>
);

export const RideIcon = (p: SVGProps<SVGSVGElement>) => (
  <Icon width={20} height={20} {...p}>
    <circle cx="6" cy="17" r="3" />
    <circle cx="18" cy="17" r="3" />
    <path d="M9 17h6l-3-7h4M6 17l3-6h3" />
  </Icon>
);

export const ParcelIcon = (p: SVGProps<SVGSVGElement>) => (
  <Icon width={20} height={20} {...p}>
    <path d="M3 7l9-4 9 4-9 4-9-4z" />
    <path d="M3 7v10l9 4 9-4V7M12 11v10" />
  </Icon>
);

export const PinIcon = (p: SVGProps<SVGSVGElement>) => (
  <Icon width={24} height={24} {...p}>
    <path d="M12 21s-7-6.2-7-11.5A7 7 0 0 1 19 9.5C19 14.8 12 21 12 21z" />
    <circle cx="12" cy="9.5" r="2.5" />
  </Icon>
);

export const ListIcon = (p: SVGProps<SVGSVGElement>) => (
  <Icon width={24} height={24} {...p}>
    <path d="M8 6h12M8 12h12M8 18h12M4 6h.01M4 12h.01M4 18h.01" />
  </Icon>
);

export const UserIcon = (p: SVGProps<SVGSVGElement>) => (
  <Icon width={24} height={24} {...p}>
    <circle cx="12" cy="8" r="4" />
    <path d="M4 21c1.5-4 4.5-6 8-6s6.5 2 8 6" />
  </Icon>
);

export const CloseIcon = (p: SVGProps<SVGSVGElement>) => (
  <Icon {...p}>
    <path d="M6 6l12 12M18 6L6 18" />
  </Icon>
);

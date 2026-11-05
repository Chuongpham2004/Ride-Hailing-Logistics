import type { UserStatus } from '@/shared/api/types';
import { Badge } from '@/shared/ui/StatusBadge';
import { USER_STATUS_NAMES } from './text';

const TONES = { ACTIVE: 'green', LOCKED: 'red', DISABLED: 'grey' } as const;

export function UserStatusBadge({ status }: { status: UserStatus }) {
  return <Badge tone={TONES[status]}>{USER_STATUS_NAMES[status]}</Badge>;
}

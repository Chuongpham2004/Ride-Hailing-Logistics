import { useQueryClient } from '@tanstack/react-query';
import { ContactVerification } from '@/shared/auth/ContactVerification';
import { useSession } from '@/shared/auth/session';
import { profileKey } from './profile';

/** Step 4: the server only accepts a profile whose owner has verified an email or a phone number. */
export function ContactStep() {
  const user = useSession((state) => state.user);
  const queryClient = useQueryClient();
  // missingRequirements changes once a contact is verified.
  const reload = () => queryClient.invalidateQueries({ queryKey: profileKey });
  if (!user) return null;
  return (
    <div className="flex flex-col gap-3">
      {user.email && (
        <ContactVerification
          channel="email"
          label="Email"
          value={user.email}
          verified={user.emailVerified}
          onVerified={reload}
        />
      )}
      {user.phone && (
        <ContactVerification
          channel="phone"
          label="Số điện thoại"
          value={user.phone}
          verified={user.phoneVerified}
          onVerified={reload}
        />
      )}
      <p className="m-0 text-sm text-curb">Chỉ cần xác minh một trong hai.</p>
    </div>
  );
}

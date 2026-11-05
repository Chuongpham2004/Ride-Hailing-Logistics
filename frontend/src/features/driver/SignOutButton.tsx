import { useState } from 'react';
import { useNavigate } from 'react-router';
import { logout } from '@/shared/auth/session';
import { Button } from '@/shared/ui/Button';

export function SignOutButton({ className = '' }: { className?: string }) {
  const navigate = useNavigate();
  const [leaving, setLeaving] = useState(false);
  return (
    <Button
      variant="secondary"
      busy={leaving}
      className={className}
      onClick={async () => {
        setLeaving(true);
        await logout();
        navigate('/login', { replace: true });
      }}
    >
      Đăng xuất
    </Button>
  );
}

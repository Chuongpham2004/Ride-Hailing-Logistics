import { Navigate, Outlet, useLocation } from 'react-router';
import { canEnter, homeFor, type Area } from '@/shared/auth/areas';
import { useSession } from '@/shared/auth/session';
import { Splash } from '@/shared/ui/Splash';

/**
 * Sends people to the login page or to their own area. For convenience only: the server checks
 * every request (403 / 404) whatever this lets through.
 */
export function RequireArea({ area }: { area: Area }) {
  const { status, user } = useSession();
  const location = useLocation();

  if (status === 'loading') return <Splash />;
  if (status === 'anonymous' || !user) {
    const next = encodeURIComponent(location.pathname + location.search);
    return <Navigate to={`/login?next=${next}`} replace />;
  }
  if (!canEnter(area, user.roles)) return <Navigate to={homeFor(user.roles)} replace />;
  return <Outlet />;
}

/** "/" goes to the signed-in user's area, or to the login page. */
export function HomeRedirect() {
  const { status, user } = useSession();
  if (status === 'loading') return <Splash />;
  return <Navigate to={user ? homeFor(user.roles) : '/login'} replace />;
}

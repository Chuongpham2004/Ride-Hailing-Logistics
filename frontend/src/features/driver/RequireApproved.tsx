import { Navigate, Outlet } from 'react-router';
import { ApiError } from '@/shared/api/ApiError';
import { messageFor } from '@/shared/api/messages';
import { Button } from '@/shared/ui/Button';
import { useDriverProfile } from './profile';

/**
 * The working screens (home, trips, earnings, history, profile) are for approved drivers only
 * (frontend-design.md §4.3): no profile yet or sent back for changes → onboarding; submitted,
 * rejected or suspended → the review page. Convenience only: the server refuses to put an
 * unapproved driver online whatever this shows.
 */
export default function RequireApproved() {
  const { data: profile, isPending, error, refetch } = useDriverProfile();

  if (isPending) {
    return <div aria-hidden="true" className="m-5 h-40 animate-pulse rounded-xl bg-concrete-2" />;
  }
  if (error instanceof ApiError && error.status === 404) return <Navigate to="/driver/onboarding" replace />;
  if (error || !profile) {
    return (
      <div className="flex flex-col gap-3 px-5 py-8">
        <p role="alert" className="m-0 text-[15px]">
          {messageFor(error)}
        </p>
        <Button variant="secondary" onClick={() => void refetch()}>
          Thử lại
        </Button>
      </div>
    );
  }
  if (profile.reviewStatus === 'DRAFT') return <Navigate to="/driver/onboarding" replace />;
  if (profile.reviewStatus !== 'APPROVED') return <Navigate to="/driver/review" replace />;
  return <Outlet />;
}

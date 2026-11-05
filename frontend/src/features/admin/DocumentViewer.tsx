import { useEffect, useMemo, useRef } from 'react';
import { messageFor } from '@/shared/api/messages';
import type { DriverDocument } from '@/shared/api/types';
import { Button } from '@/shared/ui/Button';
import { Dialog } from '@/shared/ui/Dialog';
import { useDocumentFile } from './drivers';

/**
 * A blob: URL for the file, released once nothing shows it any more. The release waits a moment
 * so React's development double mount (unmount, mount again) does not revoke a URL still in use.
 */
function useObjectUrl(blob: Blob | undefined): string | null {
  const url = useMemo(() => (blob ? URL.createObjectURL(blob) : null), [blob]);
  const release = useRef<number | undefined>(undefined);
  useEffect(() => {
    window.clearTimeout(release.current);
    if (!url) return;
    return () => {
      release.current = window.setTimeout(() => URL.revokeObjectURL(url), 1000);
    };
  }, [url]);
  return url;
}

/**
 * Shows a document's scan to the reviewer. Photos open in place; a PDF is offered as a download,
 * since a driver's file is never rendered by the app itself.
 */
export function DocumentViewer({
  driverId,
  document,
  title,
  onClose,
}: {
  driverId: string;
  document: DriverDocument;
  title: string;
  onClose: () => void;
}) {
  const file = useDocumentFile(driverId, document.id);
  const url = useObjectUrl(file.data);
  const image = file.data?.type.startsWith('image/') ?? false;

  return (
    <Dialog title={title} onClose={onClose} wide>
      {file.isPending && <div aria-hidden="true" className="h-80 animate-pulse rounded-xl bg-concrete-2" />}
      {file.isError && (
        <p role="alert" className="m-0 text-[15px]">
          {messageFor(file.error)}
        </p>
      )}
      {url && image && (
        <img src={url} alt={title} className="max-h-[70dvh] w-full rounded-lg bg-concrete-2 object-contain" />
      )}
      {url && !image && (
        <p className="m-0 text-[15px]">
          Tệp này không phải ảnh.{' '}
          <a
            href={url}
            download={`${document.type.toLowerCase()}-${document.id}.pdf`}
            className="font-semibold"
          >
            Tải về để xem
          </a>
        </p>
      )}
      <div className="flex justify-end">
        <Button size="compact" variant="secondary" onClick={onClose}>
          Đóng
        </Button>
      </div>
    </Dialog>
  );
}

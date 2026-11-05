import { useState } from 'react';
import { useNavigate } from 'react-router';
import { Button } from '@/shared/ui/Button';
import { FIELD } from './text';
import { UUID } from './trips';

/**
 * Staff often get an id from a customer, a driver or a log line: type it and open the page.
 * `to` builds the address from the id.
 */
export function IdLookup({
  label,
  to,
  initial = '',
}: {
  label: string;
  to: (id: string) => string;
  initial?: string;
}) {
  const navigate = useNavigate();
  const [id, setId] = useState(initial);
  const [invalid, setInvalid] = useState(false);
  return (
    <form
      className="flex items-start gap-2"
      onSubmit={(event) => {
        event.preventDefault();
        const value = id.trim();
        if (!UUID.test(value)) {
          setInvalid(true);
          return;
        }
        navigate(to(value.toLowerCase()));
      }}
    >
      <label className="flex flex-col gap-1 text-sm font-semibold">
        <span className="sr-only">{label}</span>
        <input
          className={`${FIELD} w-96`}
          placeholder={label}
          value={id}
          aria-invalid={invalid || undefined}
          onChange={(e) => {
            setId(e.target.value);
            setInvalid(false);
          }}
        />
        {invalid && <span className="font-normal text-stop">Mã phải có dạng UUID</span>}
      </label>
      <Button size="compact" variant="secondary" type="submit">
        Mở
      </Button>
    </form>
  );
}

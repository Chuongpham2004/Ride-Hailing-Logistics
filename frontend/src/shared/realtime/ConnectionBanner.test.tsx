import { act, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ConnectionBanner } from './ConnectionBanner';
import { realtime } from './realtime';

function setOnline(value: boolean) {
  vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(value);
  act(() => {
    window.dispatchEvent(new Event(value ? 'online' : 'offline'));
  });
}

afterEach(() => vi.restoreAllMocks());

describe('ConnectionBanner', () => {
  it('says the device is offline, then hides when the network is back', () => {
    render(<ConnectionBanner live={false} />);
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
    setOnline(false);
    expect(screen.getByRole('status')).toHaveTextContent('Không có mạng');
    setOnline(true);
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });

  it('asks the realtime connection to retry at once when the network is back', () => {
    const retry = vi.spyOn(realtime, 'retryNow');
    window.dispatchEvent(new Event('online'));
    expect(retry).toHaveBeenCalled();
  });
});

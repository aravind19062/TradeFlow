import { useState } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { Modal } from './ui';

/** Like the real dialogs: `onClose` is a new function on every render, and typing re-renders. */
function TypingDialog({ onClose }: { onClose: () => void }) {
  const [code, setCode] = useState('');
  return (
    <Modal open onClose={() => onClose()} title="Confirm it's you">
      <input aria-label="Code" value={code} onChange={(e) => setCode(e.target.value)} />
    </Modal>
  );
}

describe('Modal', () => {
  afterEach(cleanup);

  it('moves focus into the dialog when it opens', () => {
    render(<TypingDialog onClose={() => {}} />);
    expect(document.activeElement).toBe(screen.getByRole('dialog'));
  });

  it('leaves the cursor in the field on every keystroke', () => {
    render(<TypingDialog onClose={() => {}} />);
    const input = screen.getByLabelText('Code');
    input.focus();
    for (const value of ['1', '12', '123', '1234', '12345', '123456']) {
      fireEvent.change(input, { target: { value } });
      expect(document.activeElement).toBe(input);
    }
    expect(input).toHaveValue('123456');
  });

  it('closes on Escape using the latest onClose', () => {
    const onClose = vi.fn();
    render(<TypingDialog onClose={onClose} />);
    fireEvent.change(screen.getByLabelText('Code'), { target: { value: '1' } });
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(onClose).toHaveBeenCalledTimes(1);
  });
});

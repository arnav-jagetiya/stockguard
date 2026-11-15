import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { Button } from '../Button';
import { Badge } from '../Badge';
import { Card } from '../Card';
import { EmptyState } from '../EmptyState';
import { Modal } from '../Modal';

describe('Common Component Library', () => {
  it('renders Button with variants and triggers onClick', () => {
    const handleClick = vi.fn();
    render(
      <Button variant="primary" onClick={handleClick}>
        Execute Action
      </Button>
    );

    const btn = screen.getByRole('button', { name: /execute action/i });
    expect(btn).toBeInTheDocument();
    fireEvent.click(btn);
    expect(handleClick).toHaveBeenCalledTimes(1);
  });

  it('disables Button and renders spinner when loading', () => {
    render(<Button loading>Processing</Button>);
    const btn = screen.getByRole('button');
    expect(btn).toBeDisabled();
    expect(btn.querySelector('svg.animate-spin')).toBeInTheDocument();
  });

  it('renders Badge with correct status variants', () => {
    const { rerender } = render(<Badge variant="success">Synchronized</Badge>);
    expect(screen.getByText('Synchronized')).toHaveClass('text-emerald-400');

    rerender(<Badge variant="danger">Discrepancy</Badge>);
    expect(screen.getByText('Discrepancy')).toHaveClass('text-rose-400');
  });

  it('renders Card with content children', () => {
    render(
      <Card>
        <span data-testid="card-content">Card Body</span>
      </Card>
    );
    expect(screen.getByTestId('card-content')).toBeInTheDocument();
  });

  it('renders EmptyState with action trigger', () => {
    const onAction = vi.fn();
    render(
      <EmptyState
        title="No Inventory Balances"
        description="No current stock records registered."
        actionLabel="Initialize Stock"
        onAction={onAction}
      />
    );

    expect(screen.getByText('No Inventory Balances')).toBeInTheDocument();
    expect(screen.getByText('No current stock records registered.')).toBeInTheDocument();

    const actionBtn = screen.getByRole('button', { name: /initialize stock/i });
    fireEvent.click(actionBtn);
    expect(onAction).toHaveBeenCalledTimes(1);
  });

  it('renders Modal and responds to close trigger', () => {
    const handleClose = vi.fn();
    render(
      <Modal isOpen={true} onClose={handleClose} title="Triage Discrepancy">
        <p>Modal Body Content</p>
      </Modal>
    );

    expect(screen.getByText('Triage Discrepancy')).toBeInTheDocument();
    expect(screen.getByText('Modal Body Content')).toBeInTheDocument();

    // Trigger escape key
    fireEvent.keyDown(window, { key: 'Escape', code: 'Escape' });
    expect(handleClose).toHaveBeenCalled();
  });
});

import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { Button } from '../Button';
import { Badge } from '../Badge';
import { Card } from '../Card';
import { EmptyState } from '../EmptyState';
import { Modal } from '../Modal';
import { ConfirmDialog } from '../ConfirmDialog';
import { Skeleton, TableSkeleton } from '../Skeleton';
import { ThemeSelector } from '../ThemeSelector';
import { ThemeTray } from '../ThemeTray';
import { ThemeProvider } from '../../../context/ThemeContext';

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

  it('renders Badge with correct status variants and dark mode classes', () => {
    const { rerender } = render(<Badge variant="success">Synchronized</Badge>);
    const successBadge = screen.getByText('Synchronized');
    expect(successBadge).toHaveClass('text-[#456B1F]');
    expect(successBadge).toHaveClass('dark:text-[#C6F36B]');

    rerender(<Badge variant="danger">Discrepancy</Badge>);
    const dangerBadge = screen.getByText('Discrepancy');
    expect(dangerBadge).toHaveClass('text-rose-800');
    expect(dangerBadge).toHaveClass('dark:text-rose-400');
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

  it('renders ConfirmDialog and responds to confirm and cancel', () => {
    const handleConfirm = vi.fn();
    const handleClose = vi.fn();

    render(
      <ConfirmDialog
        isOpen={true}
        onClose={handleClose}
        onConfirm={handleConfirm}
        title="Delete Resource"
        message="Are you sure you want to proceed?"
        confirmText="Confirm Delete"
        variant="danger"
      />
    );

    expect(screen.getByText('Delete Resource')).toBeInTheDocument();
    expect(screen.getByText('Are you sure you want to proceed?')).toBeInTheDocument();

    const confirmBtn = screen.getByRole('button', { name: /confirm delete/i });
    fireEvent.click(confirmBtn);
    expect(handleConfirm).toHaveBeenCalledTimes(1);

    const cancelBtn = screen.getByRole('button', { name: /cancel/i });
    fireEvent.click(cancelBtn);
    expect(handleClose).toHaveBeenCalledTimes(1);
  });

  it('renders Skeleton and TableSkeleton components with correct DOM structure', () => {
    const { container } = render(
      <div>
        <Skeleton className="h-6 w-24" />
        <TableSkeleton rows={3} cols={4} />
      </div>
    );

    const skeletons = container.querySelectorAll('.animate-pulse');
    expect(skeletons.length).toBeGreaterThan(0);
  });

  it('renders ThemeSelector and allows switching theme mode', () => {
    render(
      <ThemeProvider>
        <ThemeSelector />
      </ThemeProvider>
    );

    const lightBtn = screen.getByTitle(/light/i);
    const darkBtn = screen.getByTitle(/dark/i);
    const systemBtn = screen.getByTitle(/system/i);

    expect(lightBtn).toBeInTheDocument();
    expect(darkBtn).toBeInTheDocument();
    expect(systemBtn).toBeInTheDocument();

    fireEvent.click(lightBtn);
    expect(localStorage.getItem('stockguard-theme')).toBe('light');

    fireEvent.click(darkBtn);
    expect(localStorage.getItem('stockguard-theme')).toBe('dark');
  });

  it('renders ThemeTray popover, reveals options and switches theme', () => {
    render(
      <ThemeProvider>
        <ThemeTray />
      </ThemeProvider>
    );

    // Initial trigger button
    const trigger = screen.getByRole('button', { name: /theme mode/i });
    expect(trigger).toBeInTheDocument();
    expect(trigger).toHaveAttribute('aria-expanded', 'false');

    // Click trigger to open popover tray
    fireEvent.click(trigger);
    expect(trigger).toHaveAttribute('aria-expanded', 'true');

    // Verify popover header and options
    expect(screen.getByText(/mineral & signal appearance/i)).toBeInTheDocument();
    expect(screen.getByRole('menuitem', { name: /warm mineral/i })).toBeInTheDocument();
    expect(screen.getByRole('menuitem', { name: /midnight mineral/i })).toBeInTheDocument();
    expect(screen.getByRole('menuitem', { name: /system preference/i })).toBeInTheDocument();

    // Select Dark option
    const darkOption = screen.getByRole('menuitem', { name: /midnight mineral/i });
    fireEvent.click(darkOption);

    expect(localStorage.getItem('stockguard-theme')).toBe('dark');
    expect(trigger).toHaveAttribute('aria-expanded', 'false');
  });
});

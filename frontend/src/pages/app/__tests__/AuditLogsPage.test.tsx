import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { AuditLogsPage } from '../AuditLogsPage';
import * as auditApi from '../../../api/auditLogs';
import type { AuditLogPageResponse } from '../../../types/audit';

vi.mock('../../../api/auditLogs');

const mockPageResponse: AuditLogPageResponse = {
  content: [
    {
      id: 'log-1',
      organizationId: 'org-1',
      actorUserId: '00000000-0000-0000-0000-000000000001',
      action: 'CREATE_ORDER',
      entityType: 'ORDER',
      entityId: '11111111-1111-1111-1111-111111111111',
      beforeState: {},
      afterState: { orderNumber: 'ORD-001' },
      metadata: {},
      createdAt: '2026-01-01T12:00:00Z',
    },
  ],
  page: 0,
  size: 20,
  totalElements: 1,
  totalPages: 1,
  first: true,
  last: true,
};

describe('AuditLogsPage Security & Validation', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(auditApi.getAuditLogsApi).mockResolvedValue(mockPageResponse);
  });

  it('renders initial audit logs successfully', async () => {
    render(<AuditLogsPage />);

    await waitFor(() => {
      expect(screen.getByText('CREATE_ORDER')).toBeInTheDocument();
      expect(screen.getByText('ORDER')).toBeInTheDocument();
    });

    expect(screen.getByText((_content, element) => {
      return element?.tagName.toLowerCase() === 'span' && element.textContent?.includes('total audit records') === true;
    })).toBeInTheDocument();
    expect(auditApi.getAuditLogsApi).toHaveBeenCalledWith(
      expect.objectContaining({ page: 0, size: 20 })
    );
  });

  it('displays validation warning and suppresses API call when an invalid UUID is entered', async () => {
    render(<AuditLogsPage />);

    await waitFor(() => {
      expect(screen.getByText('CREATE_ORDER')).toBeInTheDocument();
    });

    const initialCallCount = vi.mocked(auditApi.getAuditLogsApi).mock.calls.length;

    const entityInput = screen.getByLabelText(/Filter by entity ID/i);
    fireEvent.change(entityInput, { target: { value: 'not-a-valid-uuid' } });

    // Shows validation hint
    await waitFor(() => {
      expect(screen.getByText(/Invalid UUID format/i)).toBeInTheDocument();
    });

    // Clicking Apply Filter should NOT call API with invalid UUID
    const applyBtn = screen.getByText('Apply Filter');
    fireEvent.click(applyBtn);

    // Call count should NOT have increased with an invalid entityId parameter
    expect(vi.mocked(auditApi.getAuditLogsApi).mock.calls.length).toBe(initialCallCount);
  });

  it('passes valid UUID parameter when full 36-character UUID is entered', async () => {
    render(<AuditLogsPage />);

    await waitFor(() => {
      expect(screen.getByText('CREATE_ORDER')).toBeInTheDocument();
    });

    const validUuid = '12345678-1234-1234-1234-123456789abc';
    const entityInput = screen.getByLabelText(/Filter by entity ID/i);
    fireEvent.change(entityInput, { target: { value: validUuid } });

    await waitFor(() => {
      expect(screen.queryByText(/Invalid UUID format/i)).not.toBeInTheDocument();
    });

    const applyBtn = screen.getByText('Apply Filter');
    fireEvent.click(applyBtn);

    await waitFor(() => {
      expect(auditApi.getAuditLogsApi).toHaveBeenCalledWith(
        expect.objectContaining({ entityId: validUuid })
      );
    });
  });
});

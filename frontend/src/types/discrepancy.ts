export type DiscrepancyType = 'DELTA_MISMATCH' | 'MISSING_BALANCE_ROW' | 'MISSING_LEDGER_ENTRIES';
export type DiscrepancyStatus = 'OPEN' | 'ACKNOWLEDGED' | 'INVESTIGATING' | 'RESOLVED' | 'REJECTED';
export type ResolutionSource = 'OPERATOR_OVERRIDE' | 'AUTOMATED_AUTO_HEAL';

export interface Discrepancy {
  id: string;
  organizationId: string;
  productId: string;
  warehouseId: string;
  discrepancyType: DiscrepancyType;
  status: DiscrepancyStatus;
  observedBalanceOnHand: number;
  observedLedgerOnHand: number;
  observedBalanceReserved: number;
  observedLedgerReserved: number;
  consecutiveDetectionCount: number;
  firstDetectedAt: string;
  lastDetectedAt: string;
  lastVerifiedAt?: string | null;
  assignedToUserId?: string | null;
  assignedToUserActive?: boolean | null;
  investigationNotes?: string | null;
  resolutionSource?: ResolutionSource | null;
  resolutionReason?: string | null;
  resolvedAt?: string | null;
  resolvedByUserId?: string | null;
  version: number;
}

export interface DiscrepancyPageResponse {
  items: Discrepancy[];
  totalCount: number;
  limit: number;
  offset: number;
  content?: Discrepancy[];
  totalElements?: number;
}

export interface UpdateDiscrepancyRequest {
  version: number;
  status?: DiscrepancyStatus;
  assignedToUserId?: string | null;
  investigationNotes?: string;
  resolutionReason?: string;
}

export interface ReconciliationReport {
  organizationId: string;
  totalBalancesInspected: number;
  matchingBalances: number;
  discrepantBalances: number;
  reconciledAt: string;
  discrepancies: Discrepancy[];
}

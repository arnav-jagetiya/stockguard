import React, { useEffect, useState, useCallback, useRef } from 'react';
import { 
  FileText, 
  Search, 
  Filter, 
  RotateCw, 
  ChevronLeft, 
  ChevronRight, 
  Eye, 
  Calendar, 
  User, 
  Hash, 
  Layers,
  Copy,
  Check,
  X,
  AlertCircle
} from 'lucide-react';
import { Card } from '../../components/common/Card';
import { Button } from '../../components/common/Button';
import { Badge } from '../../components/common/Badge';
import { Modal } from '../../components/common/Modal';
import { TableSkeleton } from '../../components/common/Skeleton';
import { EmptyState } from '../../components/common/EmptyState';
import { getAuditLogsApi } from '../../api/auditLogs';
import type { AuditLogFilterParams } from '../../api/auditLogs';
import type { AuditLog, AuditLogPageResponse } from '../../types/audit';
import { useDebounce } from '../../hooks/useDebounce';
import { isAbortError } from '../../api/client';

const UUID_REGEX = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export const AuditLogsPage: React.FC = () => {
  const [data, setData] = useState<AuditLogPageResponse | null>(null);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);

  // Filters
  const [actionFilter, setActionFilter] = useState('');
  const [entityTypeFilter, setEntityTypeFilter] = useState('');
  const [entityIdFilter, setEntityIdFilter] = useState('');
  const [actorUserIdFilter, setActorUserIdFilter] = useState('');
  const [startDateFilter, setStartDateFilter] = useState('');
  const [endDateFilter, setEndDateFilter] = useState('');
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);

  const debouncedAction = useDebounce(actionFilter, 300);
  const debouncedEntityType = useDebounce(entityTypeFilter, 300);
  const debouncedEntityId = useDebounce(entityIdFilter, 300);
  const debouncedActorUserId = useDebounce(actorUserIdFilter, 300);

  const isEntityIdValid = !entityIdFilter.trim() || UUID_REGEX.test(entityIdFilter.trim());
  const isActorUserIdValid = !actorUserIdFilter.trim() || UUID_REGEX.test(actorUserIdFilter.trim());
  const isDateRangeValid = !(startDateFilter && endDateFilter) || (new Date(startDateFilter) <= new Date(endDateFilter));

  // Inspect Modal
  const [selectedLog, setSelectedLog] = useState<AuditLog | null>(null);
  const [copiedKey, setCopiedKey] = useState<string | null>(null);

  const abortControllerRef = useRef<AbortController | null>(null);

  const fetchLogs = useCallback(async () => {
    // If entered UUIDs or date range are invalid, prevent sending bad requests to backend
    if (entityIdFilter.trim() && !UUID_REGEX.test(entityIdFilter.trim())) {
      return;
    }
    if (actorUserIdFilter.trim() && !UUID_REGEX.test(actorUserIdFilter.trim())) {
      return;
    }
    if (!isDateRangeValid) {
      return;
    }

    if (abortControllerRef.current) {
      abortControllerRef.current.abort();
    }
    const controller = new AbortController();
    abortControllerRef.current = controller;

    setLoading(true);
    setError(null);
    try {
      const params: AuditLogFilterParams = {
        page,
        size,
        signal: controller.signal,
      };
      if (debouncedAction.trim()) params.action = debouncedAction.trim();
      if (debouncedEntityType.trim()) params.entityType = debouncedEntityType.trim();
      if (debouncedEntityId.trim() && UUID_REGEX.test(debouncedEntityId.trim())) {
        params.entityId = debouncedEntityId.trim();
      }
      if (debouncedActorUserId.trim() && UUID_REGEX.test(debouncedActorUserId.trim())) {
        params.actorUserId = debouncedActorUserId.trim();
      }
      if (startDateFilter) {
        const d = new Date(startDateFilter);
        if (!isNaN(d.getTime())) params.startDate = d.toISOString();
      }
      if (endDateFilter) {
        const d = new Date(endDateFilter);
        if (!isNaN(d.getTime())) params.endDate = d.toISOString();
      }

      const res = await getAuditLogsApi(params);
      setData(res);
    } catch (err: unknown) {
      if (isAbortError(err)) return;
      setError(err instanceof Error ? err.message : 'Failed to retrieve audit log entries');
    } finally {
      setLoading(false);
    }
  }, [page, size, debouncedAction, debouncedEntityType, debouncedEntityId, debouncedActorUserId, startDateFilter, endDateFilter, isDateRangeValid, entityIdFilter, actorUserIdFilter]);

  useEffect(() => {
    fetchLogs();
    return () => {
      if (abortControllerRef.current) {
        abortControllerRef.current.abort();
      }
    };
  }, [fetchLogs]);

  // Reset page when search filters change
  useEffect(() => {
    setPage(0);
  }, [debouncedAction, debouncedEntityType, debouncedEntityId, debouncedActorUserId, startDateFilter, endDateFilter]);

  const handleResetFilters = () => {
    setActionFilter('');
    setEntityTypeFilter('');
    setEntityIdFilter('');
    setActorUserIdFilter('');
    setStartDateFilter('');
    setEndDateFilter('');
    setPage(0);
  };

  const copyToClipboard = (text: string, key: string) => {
    navigator.clipboard.writeText(text);
    setCopiedKey(key);
    setTimeout(() => setCopiedKey(null), 2000);
  };

  const formatTimestamp = (iso: string) => {
    try {
      const d = new Date(iso);
      return d.toLocaleString(undefined, {
        month: 'short',
        day: '2-digit',
        year: 'numeric',
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit',
        hour12: false
      });
    } catch {
      return iso;
    }
  };

  const getActionBadgeVariant = (action: string): 'default' | 'success' | 'warning' | 'danger' | 'info' => {
    const act = action.toUpperCase();
    if (act.includes('CREATE') || act.includes('RECEIVE') || act.includes('RESOLVE')) return 'success';
    if (act.includes('DELETE') || act.includes('REJECT') || act.includes('CANCEL')) return 'danger';
    if (act.includes('UPDATE') || act.includes('ADJUST') || act.includes('TRANSFER')) return 'warning';
    return 'info';
  };

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 pb-2 border-b border-[#D9E1D7] dark:border-[#26362E]">
        <div>
          <div className="flex items-center gap-3">
            <h1 className="text-2xl font-bold tracking-tight text-[#18231D] dark:text-[#EEF3EA]">Immutable Audit Trail</h1>
            <Badge variant="default" className="text-xs font-mono uppercase bg-[#147C68]/10 text-[#147C68] dark:text-[#6CD8C0] border border-[#147C68]/30">
              Append-Only Event Ledger
            </Badge>
          </div>
          <p className="text-xs text-[#657369] dark:text-[#9AA99E] mt-1">
            Tamper-evident system of record capturing tenant mutations, actor IDs, timestamps, and full state diffs.
          </p>
        </div>
        <div className="flex items-center gap-2">
          <Button 
            variant="secondary" 
            size="sm" 
            onClick={fetchLogs} 
            loading={loading}
            icon={<RotateCw className="w-3.5 h-3.5" />}
          >
            Refresh
          </Button>
        </div>
      </div>

      {error && (
        <div className="p-4 rounded-lg bg-rose-500/10 border border-rose-500/20 text-rose-600 dark:text-rose-400 text-sm">
          {error}
        </div>
      )}

      {/* Filter Toolbar */}
      <Card className="p-4 border-[#D9E1D7] dark:border-[#26362E] bg-white dark:bg-[#111A17] shadow-sm">
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 xl:grid-cols-6 gap-3">
          <div>
            <label className="block text-xs font-medium text-[#657369] dark:text-[#9AA99E] mb-1">Action</label>
            <div className="relative">
              <Search className="w-3.5 h-3.5 absolute left-3 top-1/2 -translate-y-1/2 text-[#8A988E] dark:text-[#6C7D71] pointer-events-none" />
              <input
                type="text"
                aria-label="Filter by action"
                value={actionFilter}
                onChange={(e) => setActionFilter(e.target.value)}
                placeholder="e.g. CREATE_ORDER"
                className="w-full pl-8 pr-7 py-1.5 text-xs rounded-md bg-[#E9EEE6]/50 dark:bg-[#1B2922]/50 border border-[#D9E1D7] dark:border-[#26362E] text-[#18231D] dark:text-[#EEF3EA] placeholder-[#8A988E] dark:placeholder-[#6C7D71] focus:outline-none focus:border-[#456B1F] dark:focus:border-[#C6F36B] font-mono caret-[#18231D] dark:caret-[#EEF3EA]"
              />
              {actionFilter && (
                <button
                  type="button"
                  onClick={() => setActionFilter('')}
                  className="absolute right-2 top-1/2 -translate-y-1/2 text-[#8A988E] hover:text-[#18231D] dark:hover:text-[#EEF3EA]"
                  aria-label="Clear action filter"
                >
                  <X className="w-3 h-3" />
                </button>
              )}
            </div>
          </div>

          <div>
            <label className="block text-xs font-medium text-[#657369] dark:text-[#9AA99E] mb-1">Entity Type</label>
            <div className="relative">
              <Layers className="w-3.5 h-3.5 absolute left-3 top-1/2 -translate-y-1/2 text-[#8A988E] dark:text-[#6C7D71] pointer-events-none" />
              <input
                type="text"
                aria-label="Filter by entity type"
                value={entityTypeFilter}
                onChange={(e) => setEntityTypeFilter(e.target.value)}
                placeholder="e.g. INVENTORY_BALANCE"
                className="w-full pl-8 pr-7 py-1.5 text-xs rounded-md bg-[#E9EEE6]/50 dark:bg-[#1B2922]/50 border border-[#D9E1D7] dark:border-[#26362E] text-[#18231D] dark:text-[#EEF3EA] placeholder-[#8A988E] dark:placeholder-[#6C7D71] focus:outline-none focus:border-[#456B1F] dark:focus:border-[#C6F36B] font-mono caret-[#18231D] dark:caret-[#EEF3EA]"
              />
              {entityTypeFilter && (
                <button
                  type="button"
                  onClick={() => setEntityTypeFilter('')}
                  className="absolute right-2 top-1/2 -translate-y-1/2 text-[#8A988E] hover:text-[#18231D] dark:hover:text-[#EEF3EA]"
                  aria-label="Clear entity type filter"
                >
                  <X className="w-3 h-3" />
                </button>
              )}
            </div>
          </div>

          <div>
            <label className="block text-xs font-medium text-[#657369] dark:text-[#9AA99E] mb-1">Entity ID</label>
            <div className="relative">
              <Hash className="w-3.5 h-3.5 absolute left-3 top-1/2 -translate-y-1/2 text-[#8A988E] dark:text-[#6C7D71] pointer-events-none" />
              <input
                type="text"
                aria-label="Filter by entity ID"
                value={entityIdFilter}
                onChange={(e) => setEntityIdFilter(e.target.value)}
                placeholder="UUID"
                className={`w-full pl-8 pr-7 py-1.5 text-xs rounded-md bg-[#E9EEE6]/50 dark:bg-[#1B2922]/50 border ${!isEntityIdValid ? 'border-rose-500 focus:border-rose-500' : 'border-[#D9E1D7] dark:border-[#26362E] focus:border-[#456B1F] dark:focus:border-[#C6F36B]'} text-[#18231D] dark:text-[#EEF3EA] placeholder-[#8A988E] dark:placeholder-[#6C7D71] focus:outline-none font-mono caret-[#18231D] dark:caret-[#EEF3EA]`}
              />
              {entityIdFilter && (
                <button
                  type="button"
                  onClick={() => setEntityIdFilter('')}
                  className="absolute right-2 top-1/2 -translate-y-1/2 text-[#8A988E] hover:text-[#18231D] dark:hover:text-[#EEF3EA]"
                  aria-label="Clear entity ID filter"
                >
                  <X className="w-3 h-3" />
                </button>
              )}
            </div>
            {!isEntityIdValid && (
              <p className="text-[10px] text-rose-500 dark:text-rose-400 mt-0.5 flex items-center gap-1 font-mono">
                <AlertCircle className="w-2.5 h-2.5 shrink-0" /> Invalid UUID format
              </p>
            )}
          </div>

          <div>
            <label className="block text-xs font-medium text-[#657369] dark:text-[#9AA99E] mb-1">Actor User ID</label>
            <div className="relative">
              <User className="w-3.5 h-3.5 absolute left-3 top-1/2 -translate-y-1/2 text-[#8A988E] dark:text-[#6C7D71] pointer-events-none" />
              <input
                type="text"
                aria-label="Filter by actor user ID"
                value={actorUserIdFilter}
                onChange={(e) => setActorUserIdFilter(e.target.value)}
                placeholder="UUID"
                className={`w-full pl-8 pr-7 py-1.5 text-xs rounded-md bg-[#E9EEE6]/50 dark:bg-[#1B2922]/50 border ${!isActorUserIdValid ? 'border-rose-500 focus:border-rose-500' : 'border-[#D9E1D7] dark:border-[#26362E] focus:border-[#456B1F] dark:focus:border-[#C6F36B]'} text-[#18231D] dark:text-[#EEF3EA] placeholder-[#8A988E] dark:placeholder-[#6C7D71] focus:outline-none font-mono caret-[#18231D] dark:caret-[#EEF3EA]`}
              />
              {actorUserIdFilter && (
                <button
                  type="button"
                  onClick={() => setActorUserIdFilter('')}
                  className="absolute right-2 top-1/2 -translate-y-1/2 text-[#8A988E] hover:text-[#18231D] dark:hover:text-[#EEF3EA]"
                  aria-label="Clear actor user ID filter"
                >
                  <X className="w-3 h-3" />
                </button>
              )}
            </div>
            {!isActorUserIdValid && (
              <p className="text-[10px] text-rose-500 dark:text-rose-400 mt-0.5 flex items-center gap-1 font-mono">
                <AlertCircle className="w-2.5 h-2.5 shrink-0" /> Invalid UUID format
              </p>
            )}
          </div>

          <div>
            <label className="block text-xs font-medium text-[#657369] dark:text-[#9AA99E] mb-1">From Date</label>
            <div className="relative">
              <Calendar className="w-3.5 h-3.5 absolute left-3 top-1/2 -translate-y-1/2 text-[#8A988E] dark:text-[#6C7D71] pointer-events-none" />
              <input
                type="datetime-local"
                aria-label="Filter from date"
                value={startDateFilter}
                onChange={(e) => setStartDateFilter(e.target.value)}
                className={`w-full pl-8 pr-2 py-1.5 text-xs rounded-md bg-[#E9EEE6]/50 dark:bg-[#1B2922]/50 border ${!isDateRangeValid ? 'border-rose-500' : 'border-[#D9E1D7] dark:border-[#26362E]'} text-[#18231D] dark:text-[#EEF3EA] focus:outline-none focus:border-[#456B1F] dark:focus:border-[#C6F36B] font-mono`}
              />
            </div>
          </div>

          <div>
            <label className="block text-xs font-medium text-[#657369] dark:text-[#9AA99E] mb-1">To Date</label>
            <div className="relative">
              <Calendar className="w-3.5 h-3.5 absolute left-3 top-1/2 -translate-y-1/2 text-[#8A988E] dark:text-[#6C7D71] pointer-events-none" />
              <input
                type="datetime-local"
                aria-label="Filter to date"
                value={endDateFilter}
                onChange={(e) => setEndDateFilter(e.target.value)}
                className={`w-full pl-8 pr-2 py-1.5 text-xs rounded-md bg-[#E9EEE6]/50 dark:bg-[#1B2922]/50 border ${!isDateRangeValid ? 'border-rose-500' : 'border-[#D9E1D7] dark:border-[#26362E]'} text-[#18231D] dark:text-[#EEF3EA] focus:outline-none focus:border-[#456B1F] dark:focus:border-[#C6F36B] font-mono`}
              />
            </div>
            {!isDateRangeValid && (
              <p className="text-[10px] text-rose-500 dark:text-rose-400 mt-0.5 flex items-center gap-1 font-mono">
                <AlertCircle className="w-2.5 h-2.5 shrink-0" /> From date must be before To date
              </p>
            )}
          </div>
        </div>

        <div className="mt-3 flex items-center justify-between pt-2 border-t border-[#D9E1D7] dark:border-[#26362E]">
          <div className="text-xs text-[#657369] dark:text-[#9AA99E]">
            {data ? (
              <span>Found <strong className="text-[#18231D] dark:text-[#EEF3EA] font-mono">{data.totalElements}</strong> total audit records</span>
            ) : null}
          </div>
          <div className="flex items-center gap-2">
            <Button
              variant="ghost"
              size="sm"
              onClick={handleResetFilters}
              icon={<Filter className="w-3 h-3" />}
              className="text-xs text-[#657369] dark:text-[#9AA99E] hover:text-[#18231D] dark:hover:text-[#EEF3EA]"
            >
              Reset Filters
            </Button>
            <Button
              variant="secondary"
              size="sm"
              onClick={fetchLogs}
              className="text-xs"
            >
              Apply Filter
            </Button>
          </div>
        </div>
      </Card>

      {/* Main Table */}
      <Card className="border-[#D9E1D7] dark:border-[#26362E] overflow-hidden shadow-sm">
        {loading && !data ? (
          <TableSkeleton rows={8} cols={6} />
        ) : !data || data.content.length === 0 ? (
          <EmptyState
            icon={<FileText className="w-8 h-8 text-[#456B1F] dark:text-[#C6F36B]" />}
            title="No audit log entries found"
            description="No actions matching your filter criteria have been recorded in this tenant's audit trail."
            actionLabel={
              (actionFilter || entityTypeFilter || entityIdFilter || actorUserIdFilter || startDateFilter || endDateFilter) ? 'Clear Search Filters' : undefined
            }
            onAction={
              (actionFilter || entityTypeFilter || entityIdFilter || actorUserIdFilter || startDateFilter || endDateFilter) ? handleResetFilters : undefined
            }
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-xs border-collapse">
              <thead>
                <tr className="border-b border-[#D9E1D7] dark:border-[#26362E] bg-[#E9EEE6]/60 dark:bg-[#1B2922]/60 text-[#657369] dark:text-[#9AA99E] uppercase font-mono tracking-wider">
                  <th className="py-3 px-4">Timestamp (UTC)</th>
                  <th className="py-3 px-4">Action</th>
                  <th className="py-3 px-4">Entity Type</th>
                  <th className="py-3 px-4">Entity ID</th>
                  <th className="py-3 px-4">Actor ID</th>
                  <th className="py-3 px-4 text-right">Payload</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-[#D9E1D7] dark:divide-[#26362E] text-[#18231D] dark:text-[#EEF3EA] font-sans">
                {data.content.map((log) => (
                  <tr key={log.id} className="hover:bg-[#E9EEE6]/50 dark:hover:bg-[#1B2922]/50 transition-colors group">
                    <td className="py-3 px-4 font-mono text-[#657369] dark:text-[#9AA99E] whitespace-nowrap">
                      {formatTimestamp(log.createdAt)}
                    </td>
                    <td className="py-3 px-4 whitespace-nowrap">
                      <Badge variant={getActionBadgeVariant(log.action)} className="font-mono text-[10px]">
                        {log.action}
                      </Badge>
                    </td>
                    <td className="py-3 px-4 font-mono text-[#18231D] dark:text-[#EEF3EA] whitespace-nowrap font-medium">
                      {log.entityType}
                    </td>
                    <td className="py-3 px-4 font-mono text-[#657369] dark:text-[#9AA99E] whitespace-nowrap">
                      {log.entityId ? (
                        <div className="flex items-center gap-1.5">
                          <span>{log.entityId.substring(0, 8)}…{log.entityId.substring(log.entityId.length - 4)}</span>
                          <button
                            onClick={() => copyToClipboard(log.entityId!, `entity-${log.id}`)}
                            className="opacity-0 group-hover:opacity-100 p-1 hover:bg-[#E9EEE6] dark:hover:bg-[#1B2922] rounded text-[#8A988E] hover:text-[#18231D] dark:hover:text-[#EEF3EA] transition-opacity cursor-pointer"
                            title="Copy Entity ID"
                          >
                            {copiedKey === `entity-${log.id}` ? (
                              <Check className="w-3 h-3 text-[#456B1F] dark:text-[#C6F36B]" />
                            ) : (
                              <Copy className="w-3 h-3" />
                            )}
                          </button>
                        </div>
                      ) : (
                        <span className="text-[#8A988E] dark:text-[#6C7D71]">—</span>
                      )}
                    </td>
                    <td className="py-3 px-4 font-mono text-[#657369] dark:text-[#9AA99E] whitespace-nowrap">
                      {log.actorUserId ? (
                        <div className="flex items-center gap-1.5">
                          <span className="text-[#147C68] dark:text-[#6CD8C0]">
                            {log.actorUserId.substring(0, 8)}…
                          </span>
                          <button
                            onClick={() => copyToClipboard(log.actorUserId!, `actor-${log.id}`)}
                            className="opacity-0 group-hover:opacity-100 p-1 hover:bg-[#E9EEE6] dark:hover:bg-[#1B2922] rounded text-[#8A988E] hover:text-[#18231D] dark:hover:text-[#EEF3EA] transition-opacity cursor-pointer"
                            title="Copy Actor User ID"
                          >
                            {copiedKey === `actor-${log.id}` ? (
                              <Check className="w-3 h-3 text-[#456B1F] dark:text-[#C6F36B]" />
                            ) : (
                              <Copy className="w-3 h-3" />
                            )}
                          </button>
                        </div>
                      ) : (
                        <span className="text-[#8A988E] dark:text-[#6C7D71] italic">SYSTEM / ANONYMOUS</span>
                      )}
                    </td>
                    <td className="py-3 px-4 text-right whitespace-nowrap">
                      <Button
                        variant="secondary"
                        size="sm"
                        onClick={() => setSelectedLog(log)}
                        icon={<Eye className="w-3.5 h-3.5" />}
                        className="text-xs h-7 px-2.5"
                      >
                        Inspect
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        {/* Pagination Bar */}
        {data && data.totalPages > 1 && (
          <div className="p-3 px-4 border-t border-[#D9E1D7] dark:border-[#26362E] bg-[#E9EEE6]/40 dark:bg-[#1B2922]/40 flex flex-col sm:flex-row items-center justify-between gap-3 text-xs text-[#657369] dark:text-[#9AA99E]">
            <div className="flex items-center gap-2">
              <span>Showing page <strong className="text-[#18231D] dark:text-[#EEF3EA]">{data.page + 1}</strong> of <strong className="text-[#18231D] dark:text-[#EEF3EA]">{data.totalPages}</strong> ({data.totalElements} records)</span>
              <span className="text-[#8A988E] dark:text-[#6C7D71]">|</span>
              <span>Per page:</span>
              <select
                value={size}
                onChange={(e) => { setSize(Number(e.target.value)); setPage(0); }}
                className="bg-white dark:bg-[#111A17] border border-[#D9E1D7] dark:border-[#26362E] rounded px-1.5 py-0.5 text-[#18231D] dark:text-[#EEF3EA] text-xs focus:outline-none focus:border-[#456B1F] dark:focus:border-[#C6F36B]"
              >
                <option value={10}>10</option>
                <option value={20}>20</option>
                <option value={50}>50</option>
                <option value={100}>100</option>
              </select>
            </div>

            <div className="flex items-center gap-1.5">
              <Button
                variant="secondary"
                size="sm"
                onClick={() => setPage((p) => Math.max(0, p - 1))}
                disabled={data.first || loading}
                icon={<ChevronLeft className="w-3.5 h-3.5" />}
                className="h-7 px-2"
              >
                Previous
              </Button>
              <Button
                variant="secondary"
                size="sm"
                onClick={() => setPage((p) => Math.min(data.totalPages - 1, p + 1))}
                disabled={data.last || loading}
                icon={<ChevronRight className="w-3.5 h-3.5" />}
                className="h-7 px-2 flex-row-reverse"
              >
                Next
              </Button>
            </div>
          </div>
        )}
      </Card>

      {/* Inspect State Diff Modal */}
      {selectedLog && (
        <Modal
          isOpen={!!selectedLog}
          onClose={() => setSelectedLog(null)}
          title={`Audit Record: ${selectedLog.action} (${selectedLog.entityType})`}
          size="xl"
        >
          <div className="space-y-4">
            {/* Metadata Banner */}
            <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 p-3 bg-[#E9EEE6]/50 dark:bg-[#1B2922]/50 rounded-lg border border-[#D9E1D7] dark:border-[#26362E] text-xs font-mono">
              <div>
                <span className="text-[#657369] dark:text-[#9AA99E] block text-[10px]">RECORD ID</span>
                <span className="text-[#18231D] dark:text-[#EEF3EA] select-all">{selectedLog.id}</span>
              </div>
              <div>
                <span className="text-[#657369] dark:text-[#9AA99E] block text-[10px]">TIMESTAMP</span>
                <span className="text-[#18231D] dark:text-[#EEF3EA]">{formatTimestamp(selectedLog.createdAt)}</span>
              </div>
              <div>
                <span className="text-[#657369] dark:text-[#9AA99E] block text-[10px]">ACTOR USER ID</span>
                <span className="text-[#147C68] dark:text-[#6CD8C0] select-all">{selectedLog.actorUserId || 'SYSTEM'}</span>
              </div>
              <div>
                <span className="text-[#657369] dark:text-[#9AA99E] block text-[10px]">ENTITY ID</span>
                <span className="text-[#18231D] dark:text-[#EEF3EA] select-all">{selectedLog.entityId || 'N/A'}</span>
              </div>
            </div>

            {/* Diffs & Payload Sections */}
            <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
              {/* Before State */}
              <div>
                <div className="flex items-center justify-between pb-1.5 mb-1.5 border-b border-[#D9E1D7] dark:border-[#26362E]">
                  <span className="text-xs font-medium text-[#18231D] dark:text-[#EEF3EA] flex items-center gap-1.5">
                    <span className="w-2 h-2 rounded-full bg-amber-500" />
                    Before State
                  </span>
                  <button
                    onClick={() => copyToClipboard(JSON.stringify(selectedLog.beforeState, null, 2), 'before')}
                    className="text-[11px] text-[#657369] dark:text-[#9AA99E] hover:text-[#18231D] dark:hover:text-[#EEF3EA] flex items-center gap-1 cursor-pointer"
                  >
                    {copiedKey === 'before' ? <Check className="w-3 h-3 text-[#456B1F] dark:text-[#C6F36B]" /> : <Copy className="w-3 h-3" />}
                    Copy
                  </button>
                </div>
                <pre className="p-3 bg-[#0B100F] border border-[#26362E] rounded-lg text-xs font-mono text-amber-300 dark:text-amber-200/90 overflow-x-auto max-h-64 scrollbar-thin">
                  {Object.keys(selectedLog.beforeState || {}).length > 0
                    ? JSON.stringify(selectedLog.beforeState, null, 2)
                    : '// No prior state (initial creation or stateless event)'}
                </pre>
              </div>

              {/* After State */}
              <div>
                <div className="flex items-center justify-between pb-1.5 mb-1.5 border-b border-[#D9E1D7] dark:border-[#26362E]">
                  <span className="text-xs font-medium text-[#18231D] dark:text-[#EEF3EA] flex items-center gap-1.5">
                    <span className="w-2 h-2 rounded-full bg-[#456B1F] dark:bg-[#C6F36B]" />
                    After State
                  </span>
                  <button
                    onClick={() => copyToClipboard(JSON.stringify(selectedLog.afterState, null, 2), 'after')}
                    className="text-[11px] text-[#657369] dark:text-[#9AA99E] hover:text-[#18231D] dark:hover:text-[#EEF3EA] flex items-center gap-1 cursor-pointer"
                  >
                    {copiedKey === 'after' ? <Check className="w-3 h-3 text-[#456B1F] dark:text-[#C6F36B]" /> : <Copy className="w-3 h-3" />}
                    Copy
                  </button>
                </div>
                <pre className="p-3 bg-[#0B100F] border border-[#26362E] rounded-lg text-xs font-mono text-[#C6F36B] overflow-x-auto max-h-64 scrollbar-thin">
                  {Object.keys(selectedLog.afterState || {}).length > 0
                    ? JSON.stringify(selectedLog.afterState, null, 2)
                    : '// No after state (deletion or terminal purge)'}
                </pre>
              </div>
            </div>

            {/* Metadata Payload */}
            <div>
              <div className="flex items-center justify-between pb-1.5 mb-1.5 border-b border-[#D9E1D7] dark:border-[#26362E]">
                <span className="text-xs font-medium text-[#18231D] dark:text-[#EEF3EA] flex items-center gap-1.5">
                  <span className="w-2 h-2 rounded-full bg-[#147C68] dark:bg-[#6CD8C0]" />
                  Context Metadata
                </span>
                <button
                  onClick={() => copyToClipboard(JSON.stringify(selectedLog.metadata, null, 2), 'meta')}
                  className="text-[11px] text-[#657369] dark:text-[#9AA99E] hover:text-[#18231D] dark:hover:text-[#EEF3EA] flex items-center gap-1 cursor-pointer"
                >
                  {copiedKey === 'meta' ? <Check className="w-3 h-3 text-[#456B1F] dark:text-[#C6F36B]" /> : <Copy className="w-3 h-3" />}
                  Copy
                </button>
              </div>
              <pre className="p-3 bg-[#0B100F] border border-[#26362E] rounded-lg text-xs font-mono text-[#6CD8C0] overflow-x-auto max-h-40 scrollbar-thin">
                {Object.keys(selectedLog.metadata || {}).length > 0
                  ? JSON.stringify(selectedLog.metadata, null, 2)
                  : '// No metadata headers attached'}
              </pre>
            </div>

            <div className="flex justify-end pt-2 border-t border-[#D9E1D7] dark:border-[#26362E]">
              <Button variant="secondary" onClick={() => setSelectedLog(null)}>
                Close
              </Button>
            </div>
          </div>
        </Modal>
      )}
    </div>
  );
};

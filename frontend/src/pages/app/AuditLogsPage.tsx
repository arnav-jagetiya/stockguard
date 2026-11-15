import React, { useEffect, useState, useCallback } from 'react';
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
  Check
} from 'lucide-react';
import { Card } from '../../components/common/Card';
import { Button } from '../../components/common/Button';
import { Badge } from '../../components/common/Badge';
import { Modal } from '../../components/common/Modal';
import { LoadingSpinner } from '../../components/common/LoadingSpinner';
import { EmptyState } from '../../components/common/EmptyState';
import { getAuditLogsApi } from '../../api/auditLogs';
import type { AuditLogFilterParams } from '../../api/auditLogs';
import type { AuditLog, AuditLogPageResponse } from '../../types/audit';

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

  // Inspect Modal
  const [selectedLog, setSelectedLog] = useState<AuditLog | null>(null);
  const [copiedKey, setCopiedKey] = useState<string | null>(null);

  const fetchLogs = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const params: AuditLogFilterParams = {
        page,
        size,
      };
      if (actionFilter.trim()) params.action = actionFilter.trim();
      if (entityTypeFilter.trim()) params.entityType = entityTypeFilter.trim();
      if (entityIdFilter.trim()) params.entityId = entityIdFilter.trim();
      if (actorUserIdFilter.trim()) params.actorUserId = actorUserIdFilter.trim();
      if (startDateFilter) params.startDate = new Date(startDateFilter).toISOString();
      if (endDateFilter) params.endDate = new Date(endDateFilter).toISOString();

      const res = await getAuditLogsApi(params);
      setData(res);
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'Failed to retrieve audit log entries');
    } finally {
      setLoading(false);
    }
  }, [page, size, actionFilter, entityTypeFilter, entityIdFilter, actorUserIdFilter, startDateFilter, endDateFilter]);

  useEffect(() => {
    fetchLogs();
  }, [fetchLogs]);

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
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 pb-2 border-b border-white/5">
        <div>
          <div className="flex items-center gap-3">
            <h1 className="text-2xl font-bold tracking-tight text-white">Immutable Audit Trail</h1>
            <Badge variant="default" className="text-xs font-mono uppercase bg-cyan-950/40 text-cyan-400 border border-cyan-800/40">
              SOC 2 / ISO 27001 Compliant
            </Badge>
          </div>
          <p className="text-xs text-slate-400 mt-1">
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
        <div className="p-4 rounded-lg bg-rose-500/10 border border-rose-500/20 text-rose-400 text-sm">
          {error}
        </div>
      )}

      {/* Filter Toolbar */}
      <Card className="p-4 border-white/5 bg-slate-900/50">
        <div className="grid grid-cols-1 md:grid-cols-3 lg:grid-cols-6 gap-3">
          <div>
            <label className="block text-xs font-medium text-slate-400 mb-1">Action</label>
            <div className="relative">
              <Search className="w-3.5 h-3.5 absolute left-3 top-1/2 -translate-y-1/2 text-slate-500 pointer-events-none" />
              <input
                type="text"
                value={actionFilter}
                onChange={(e) => { setActionFilter(e.target.value); setPage(0); }}
                placeholder="e.g. CREATE_ORDER"
                className="w-full pl-8 pr-3 py-1.5 text-xs rounded-md bg-slate-950/70 border border-white/10 text-slate-200 placeholder-slate-600 focus:outline-none focus:border-cyan-500 font-mono"
              />
            </div>
          </div>

          <div>
            <label className="block text-xs font-medium text-slate-400 mb-1">Entity Type</label>
            <div className="relative">
              <Layers className="w-3.5 h-3.5 absolute left-3 top-1/2 -translate-y-1/2 text-slate-500 pointer-events-none" />
              <input
                type="text"
                value={entityTypeFilter}
                onChange={(e) => { setEntityTypeFilter(e.target.value); setPage(0); }}
                placeholder="e.g. INVENTORY_BALANCE"
                className="w-full pl-8 pr-3 py-1.5 text-xs rounded-md bg-slate-950/70 border border-white/10 text-slate-200 placeholder-slate-600 focus:outline-none focus:border-cyan-500 font-mono"
              />
            </div>
          </div>

          <div>
            <label className="block text-xs font-medium text-slate-400 mb-1">Entity ID</label>
            <div className="relative">
              <Hash className="w-3.5 h-3.5 absolute left-3 top-1/2 -translate-y-1/2 text-slate-500 pointer-events-none" />
              <input
                type="text"
                value={entityIdFilter}
                onChange={(e) => { setEntityIdFilter(e.target.value); setPage(0); }}
                placeholder="UUID"
                className="w-full pl-8 pr-3 py-1.5 text-xs rounded-md bg-slate-950/70 border border-white/10 text-slate-200 placeholder-slate-600 focus:outline-none focus:border-cyan-500 font-mono"
              />
            </div>
          </div>

          <div>
            <label className="block text-xs font-medium text-slate-400 mb-1">Actor User ID</label>
            <div className="relative">
              <User className="w-3.5 h-3.5 absolute left-3 top-1/2 -translate-y-1/2 text-slate-500 pointer-events-none" />
              <input
                type="text"
                value={actorUserIdFilter}
                onChange={(e) => { setActorUserIdFilter(e.target.value); setPage(0); }}
                placeholder="UUID"
                className="w-full pl-8 pr-3 py-1.5 text-xs rounded-md bg-slate-950/70 border border-white/10 text-slate-200 placeholder-slate-600 focus:outline-none focus:border-cyan-500 font-mono"
              />
            </div>
          </div>

          <div>
            <label className="block text-xs font-medium text-slate-400 mb-1">From Date</label>
            <div className="relative">
              <Calendar className="w-3.5 h-3.5 absolute left-3 top-1/2 -translate-y-1/2 text-slate-500 pointer-events-none" />
              <input
                type="datetime-local"
                value={startDateFilter}
                onChange={(e) => { setStartDateFilter(e.target.value); setPage(0); }}
                className="w-full pl-8 pr-2 py-1.5 text-xs rounded-md bg-slate-950/70 border border-white/10 text-slate-200 focus:outline-none focus:border-cyan-500 font-mono"
              />
            </div>
          </div>

          <div>
            <label className="block text-xs font-medium text-slate-400 mb-1">To Date</label>
            <div className="relative">
              <Calendar className="w-3.5 h-3.5 absolute left-3 top-1/2 -translate-y-1/2 text-slate-500 pointer-events-none" />
              <input
                type="datetime-local"
                value={endDateFilter}
                onChange={(e) => { setEndDateFilter(e.target.value); setPage(0); }}
                className="w-full pl-8 pr-2 py-1.5 text-xs rounded-md bg-slate-950/70 border border-white/10 text-slate-200 focus:outline-none focus:border-cyan-500 font-mono"
              />
            </div>
          </div>
        </div>

        <div className="mt-3 flex items-center justify-between pt-2 border-t border-white/5">
          <div className="text-xs text-slate-500">
            {data ? (
              <span>Found <strong className="text-slate-300 font-mono">{data.totalElements}</strong> total audit records</span>
            ) : null}
          </div>
          <div className="flex items-center gap-2">
            <Button
              variant="ghost"
              size="sm"
              onClick={handleResetFilters}
              icon={<Filter className="w-3 h-3" />}
              className="text-xs text-slate-400 hover:text-slate-200"
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
      <Card className="border-white/5 overflow-hidden">
        {loading && !data ? (
          <div className="p-12 flex justify-center">
            <LoadingSpinner size="lg" text="Streaming immutable audit trail..." />
          </div>
        ) : !data || data.content.length === 0 ? (
          <EmptyState
            icon={FileText}
            title="No audit log entries found"
            description="No actions matching your filter criteria have been recorded in this tenant's audit trail."
            action={
              (actionFilter || entityTypeFilter || entityIdFilter || actorUserIdFilter || startDateFilter || endDateFilter) ? (
                <Button variant="secondary" size="sm" onClick={handleResetFilters}>
                  Clear Search Filters
                </Button>
              ) : undefined
            }
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-xs border-collapse">
              <thead>
                <tr className="border-b border-white/10 bg-slate-900/60 text-slate-400 uppercase font-mono tracking-wider">
                  <th className="py-3 px-4">Timestamp (UTC)</th>
                  <th className="py-3 px-4">Action</th>
                  <th className="py-3 px-4">Entity Type</th>
                  <th className="py-3 px-4">Entity ID</th>
                  <th className="py-3 px-4">Actor ID</th>
                  <th className="py-3 px-4 text-right">Payload</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-white/5 text-slate-300 font-sans">
                {data.content.map((log) => (
                  <tr key={log.id} className="hover:bg-slate-800/40 transition-colors group">
                    <td className="py-3 px-4 font-mono text-slate-400 whitespace-nowrap">
                      {formatTimestamp(log.createdAt)}
                    </td>
                    <td className="py-3 px-4 whitespace-nowrap">
                      <Badge variant={getActionBadgeVariant(log.action)} className="font-mono text-[10px]">
                        {log.action}
                      </Badge>
                    </td>
                    <td className="py-3 px-4 font-mono text-slate-300 whitespace-nowrap">
                      {log.entityType}
                    </td>
                    <td className="py-3 px-4 font-mono text-slate-400 whitespace-nowrap">
                      {log.entityId ? (
                        <div className="flex items-center gap-1.5">
                          <span>{log.entityId.substring(0, 8)}…{log.entityId.substring(log.entityId.length - 4)}</span>
                          <button
                            onClick={() => copyToClipboard(log.entityId!, `entity-${log.id}`)}
                            className="opacity-0 group-hover:opacity-100 p-1 hover:bg-slate-700 rounded text-slate-400 hover:text-white transition-opacity"
                            title="Copy Entity ID"
                          >
                            {copiedKey === `entity-${log.id}` ? (
                              <Check className="w-3 h-3 text-emerald-400" />
                            ) : (
                              <Copy className="w-3 h-3" />
                            )}
                          </button>
                        </div>
                      ) : (
                        <span className="text-slate-600">—</span>
                      )}
                    </td>
                    <td className="py-3 px-4 font-mono text-slate-400 whitespace-nowrap">
                      {log.actorUserId ? (
                        <div className="flex items-center gap-1.5">
                          <span className="text-cyan-400/80">
                            {log.actorUserId.substring(0, 8)}…
                          </span>
                          <button
                            onClick={() => copyToClipboard(log.actorUserId!, `actor-${log.id}`)}
                            className="opacity-0 group-hover:opacity-100 p-1 hover:bg-slate-700 rounded text-slate-400 hover:text-white transition-opacity"
                            title="Copy Actor User ID"
                          >
                            {copiedKey === `actor-${log.id}` ? (
                              <Check className="w-3 h-3 text-emerald-400" />
                            ) : (
                              <Copy className="w-3 h-3" />
                            )}
                          </button>
                        </div>
                      ) : (
                        <span className="text-slate-600 italic">SYSTEM / ANONYMOUS</span>
                      )}
                    </td>
                    <td className="py-3 px-4 text-right whitespace-nowrap">
                      <Button
                        variant="secondary"
                        size="sm"
                        onClick={() => setSelectedLog(log)}
                        icon={<Eye className="w-3.5 h-3.5" />}
                        className="text-xs h-7 px-2.5 bg-slate-800/80 hover:bg-slate-700"
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
          <div className="p-3 px-4 border-t border-white/5 bg-slate-900/40 flex flex-col sm:flex-row items-center justify-between gap-3 text-xs text-slate-400">
            <div className="flex items-center gap-2">
              <span>Showing page <strong className="text-slate-200">{data.page + 1}</strong> of <strong className="text-slate-200">{data.totalPages}</strong> ({data.totalElements} records)</span>
              <span className="text-slate-600">|</span>
              <span>Per page:</span>
              <select
                value={size}
                onChange={(e) => { setSize(Number(e.target.value)); setPage(0); }}
                className="bg-slate-950 border border-white/10 rounded px-1.5 py-0.5 text-slate-300 text-xs focus:outline-none focus:border-cyan-500"
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
            <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 p-3 bg-slate-950/70 rounded-lg border border-white/5 text-xs font-mono">
              <div>
                <span className="text-slate-500 block text-[10px]">RECORD ID</span>
                <span className="text-slate-300 select-all">{selectedLog.id}</span>
              </div>
              <div>
                <span className="text-slate-500 block text-[10px]">TIMESTAMP</span>
                <span className="text-slate-300">{formatTimestamp(selectedLog.createdAt)}</span>
              </div>
              <div>
                <span className="text-slate-500 block text-[10px]">ACTOR USER ID</span>
                <span className="text-cyan-400 select-all">{selectedLog.actorUserId || 'SYSTEM'}</span>
              </div>
              <div>
                <span className="text-slate-500 block text-[10px]">ENTITY ID</span>
                <span className="text-slate-300 select-all">{selectedLog.entityId || 'N/A'}</span>
              </div>
            </div>

            {/* Diffs & Payload Sections */}
            <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
              {/* Before State */}
              <div>
                <div className="flex items-center justify-between pb-1.5 mb-1.5 border-b border-white/5">
                  <span className="text-xs font-medium text-slate-300 flex items-center gap-1.5">
                    <span className="w-2 h-2 rounded-full bg-amber-400" />
                    Before State
                  </span>
                  <button
                    onClick={() => copyToClipboard(JSON.stringify(selectedLog.beforeState, null, 2), 'before')}
                    className="text-[11px] text-slate-400 hover:text-white flex items-center gap-1"
                  >
                    {copiedKey === 'before' ? <Check className="w-3 h-3 text-emerald-400" /> : <Copy className="w-3 h-3" />}
                    Copy
                  </button>
                </div>
                <pre className="p-3 bg-slate-950/90 border border-white/5 rounded-lg text-xs font-mono text-amber-200/90 overflow-x-auto max-h-64 scrollbar-thin">
                  {Object.keys(selectedLog.beforeState || {}).length > 0
                    ? JSON.stringify(selectedLog.beforeState, null, 2)
                    : '// No prior state (initial creation or stateless event)'}
                </pre>
              </div>

              {/* After State */}
              <div>
                <div className="flex items-center justify-between pb-1.5 mb-1.5 border-b border-white/5">
                  <span className="text-xs font-medium text-slate-300 flex items-center gap-1.5">
                    <span className="w-2 h-2 rounded-full bg-emerald-400" />
                    After State
                  </span>
                  <button
                    onClick={() => copyToClipboard(JSON.stringify(selectedLog.afterState, null, 2), 'after')}
                    className="text-[11px] text-slate-400 hover:text-white flex items-center gap-1"
                  >
                    {copiedKey === 'after' ? <Check className="w-3 h-3 text-emerald-400" /> : <Copy className="w-3 h-3" />}
                    Copy
                  </button>
                </div>
                <pre className="p-3 bg-slate-950/90 border border-white/5 rounded-lg text-xs font-mono text-emerald-200/90 overflow-x-auto max-h-64 scrollbar-thin">
                  {Object.keys(selectedLog.afterState || {}).length > 0
                    ? JSON.stringify(selectedLog.afterState, null, 2)
                    : '// No after state (deletion or terminal purge)'}
                </pre>
              </div>
            </div>

            {/* Metadata Payload */}
            <div>
              <div className="flex items-center justify-between pb-1.5 mb-1.5 border-b border-white/5">
                <span className="text-xs font-medium text-slate-300 flex items-center gap-1.5">
                  <span className="w-2 h-2 rounded-full bg-cyan-400" />
                  Context Metadata
                </span>
                <button
                  onClick={() => copyToClipboard(JSON.stringify(selectedLog.metadata, null, 2), 'meta')}
                  className="text-[11px] text-slate-400 hover:text-white flex items-center gap-1"
                >
                  {copiedKey === 'meta' ? <Check className="w-3 h-3 text-emerald-400" /> : <Copy className="w-3 h-3" />}
                  Copy
                </button>
              </div>
              <pre className="p-3 bg-slate-950/90 border border-white/5 rounded-lg text-xs font-mono text-cyan-200/90 overflow-x-auto max-h-40 scrollbar-thin">
                {Object.keys(selectedLog.metadata || {}).length > 0
                  ? JSON.stringify(selectedLog.metadata, null, 2)
                  : '// No metadata headers attached'}
              </pre>
            </div>

            <div className="flex justify-end pt-2 border-t border-white/5">
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

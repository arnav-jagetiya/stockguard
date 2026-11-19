import React, { useEffect, useState, useCallback, useMemo } from 'react';
import { 
  Users, 
  UserPlus, 
  ShieldCheck, 
  RotateCw, 
  Edit3, 
  UserX, 
  Check, 
  Copy, 
  Info,
  Search,
  X,
  RotateCcw,
} from 'lucide-react';
import { Card } from '../../components/common/Card';
import { Button } from '../../components/common/Button';
import { Badge } from '../../components/common/Badge';
import { Input } from '../../components/common/Input';
import { Select } from '../../components/common/Select';
import { Modal } from '../../components/common/Modal';
import { TableSkeleton } from '../../components/common/Skeleton';
import { ConfirmDialog } from '../../components/common/ConfirmDialog';
import { EmptyState } from '../../components/common/EmptyState';
import { useAuth } from '../../context/AuthContext';
import { useToast } from '../../context/ToastContext';
import { 
  getMembershipsApi, 
  addMemberApi, 
  updateMemberRoleApi, 
  deactivateMemberApi 
} from '../../api/organizations';
import type { OrganizationMember } from '../../types/organization';
import type { Role } from '../../types/auth';

const ROLE_OPTIONS = [
  { value: 'VIEWER', label: 'VIEWER — Read-only catalog & stock visibility' },
  { value: 'OPERATOR', label: 'OPERATOR — Inventory movements, order pipeline & triage' },
  { value: 'MANAGER', label: 'MANAGER — Operational oversight & facility management' },
  { value: 'ADMIN', label: 'ADMIN — Full administrative control & membership governance' },
];

export const TeamPage: React.FC = () => {
  const { currentOrg, user, hasPermission } = useAuth();
  const toast = useToast();
  const [members, setMembers] = useState<OrganizationMember[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);
  const [copiedKey, setCopiedKey] = useState<string | null>(null);

  // Add Member State
  const [isAddOpen, setIsAddOpen] = useState(false);
  const [addUserId, setAddUserId] = useState('');
  const [addRole, setAddRole] = useState<Role>('OPERATOR');
  const [addLoading, setAddLoading] = useState(false);
  const [addError, setAddError] = useState<string | null>(null);

  // Edit Role State
  const [editingMember, setEditingMember] = useState<OrganizationMember | null>(null);
  const [editRole, setEditRole] = useState<Role>('OPERATOR');
  const [editLoading, setEditLoading] = useState(false);
  const [editError, setEditError] = useState<string | null>(null);

  // Filters
  const [searchQuery, setSearchQuery] = useState('');
  const [roleFilter, setRoleFilter] = useState<'ALL' | Role>('ALL');
  const [statusFilter, setStatusFilter] = useState<'ALL' | 'ACTIVE' | 'INACTIVE'>('ALL');

  // Deactivate State
  const [deactivatingMember, setDeactivatingMember] = useState<OrganizationMember | null>(null);
  const [deactivateLoading, setDeactivateLoading] = useState(false);

  const canManageUsers = hasPermission('membership:manage') || currentOrg?.role === 'ADMIN';

  const filteredMembers = useMemo(() => {
    return members.filter((m) => {
      if (searchQuery.trim() && !m.userId.toLowerCase().includes(searchQuery.trim().toLowerCase())) {
        return false;
      }
      if (roleFilter !== 'ALL' && m.role !== roleFilter) {
        return false;
      }
      if (statusFilter === 'ACTIVE' && !m.active) return false;
      if (statusFilter === 'INACTIVE' && m.active) return false;
      return true;
    });
  }, [members, searchQuery, roleFilter, statusFilter]);

  const handleResetFilters = () => {
    setSearchQuery('');
    setRoleFilter('ALL');
    setStatusFilter('ALL');
  };

  const fetchMembers = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await getMembershipsApi();
      setMembers(res);
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'Failed to retrieve organization memberships');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchMembers();
  }, [fetchMembers]);

  const copyToClipboard = (text: string, key: string) => {
    navigator.clipboard.writeText(text);
    setCopiedKey(key);
    setTimeout(() => setCopiedKey(null), 2000);
  };

  const handleAddMember = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!addUserId.trim()) {
      setAddError('Please enter a valid User UUID');
      return;
    }
    setAddLoading(true);
    setAddError(null);
    try {
      await addMemberApi({
        userId: addUserId.trim(),
        role: addRole,
      });
      setIsAddOpen(false);
      setAddUserId('');
      setAddRole('OPERATOR');
      toast.success('Member access granted successfully.');
      await fetchMembers();
    } catch (err: unknown) {
      setAddError(err instanceof Error ? err.message : 'Failed to add member');
    } finally {
      setAddLoading(false);
    }
  };

  const handleUpdateRole = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!editingMember) return;
    setEditLoading(true);
    setEditError(null);
    try {
      await updateMemberRoleApi(editingMember.userId, {
        role: editRole,
      });
      setEditingMember(null);
      toast.success('Member role updated successfully.');
      await fetchMembers();
    } catch (err: unknown) {
      setEditError(err instanceof Error ? err.message : 'Failed to update member role');
    } finally {
      setEditLoading(false);
    }
  };

  const handleDeactivate = async () => {
    if (!deactivatingMember) return;
    setDeactivateLoading(true);
    try {
      await deactivateMemberApi(deactivatingMember.userId);
      toast.success('Member access revoked.');
      setDeactivatingMember(null);
      await fetchMembers();
    } catch (err: unknown) {
      toast.error(err instanceof Error ? err.message : 'Failed to deactivate member');
    } finally {
      setDeactivateLoading(false);
    }
  };

  const formatTimestamp = (iso: string) => {
    try {
      return new Date(iso).toLocaleDateString(undefined, {
        year: 'numeric',
        month: 'short',
        day: 'numeric'
      });
    } catch {
      return iso;
    }
  };

  const getRoleBadgeVariant = (role: Role): 'default' | 'success' | 'warning' | 'danger' | 'info' => {
    switch (role) {
      case 'ADMIN': return 'warning';
      case 'MANAGER': return 'info';
      case 'OPERATOR': return 'success';
      case 'VIEWER': return 'default';
      default: return 'default';
    }
  };

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 pb-2 border-b border-[#D9E1D7] dark:border-[#26362E]">
        <div>
          <div className="flex items-center gap-3">
            <h1 className="text-2xl font-bold tracking-tight text-[#18231D] dark:text-[#EEF3EA]">Team & Access Governance</h1>
            <Badge variant="default" className="text-xs font-mono uppercase bg-[#147C68]/10 text-[#147C68] dark:text-[#6CD8C0] border border-[#147C68]/30">
              RBAC v2
            </Badge>
          </div>
          <p className="text-xs text-[#657369] dark:text-[#9AA99E] mt-1">
            Manage tenant membership, least-privilege role assignments, and organizational access boundaries.
          </p>
        </div>
        <div className="flex items-center gap-2">
          <Button 
            variant="secondary" 
            size="sm" 
            onClick={fetchMembers} 
            loading={loading}
            icon={<RotateCw className="w-3.5 h-3.5" />}
          >
            Refresh
          </Button>
          {canManageUsers && (
            <Button 
              variant="primary" 
              size="sm" 
              onClick={() => { setIsAddOpen(true); setAddError(null); }}
              icon={<UserPlus className="w-3.5 h-3.5" />}
            >
              Add Member
            </Button>
          )}
        </div>
      </div>

      {error && (
        <div className="p-4 rounded-lg bg-rose-500/10 border border-rose-500/20 text-rose-600 dark:text-rose-400 text-sm">
          {error}
        </div>
      )}

      {/* RBAC Info Card */}
      <div className="grid grid-cols-1 md:grid-cols-4 gap-3">
        <div className="p-3 rounded-lg bg-white dark:bg-[#111A17] border border-[#D9E1D7] dark:border-[#26362E] text-xs">
          <div className="font-semibold text-amber-600 dark:text-amber-400 flex items-center gap-1.5 mb-1">
            <ShieldCheck className="w-3.5 h-3.5" /> ADMIN
          </div>
          <p className="text-[#657369] dark:text-[#9AA99E] text-[11px] leading-relaxed">
            Tenant administration, full membership governance, facility configuration, and policy control.
          </p>
        </div>
        <div className="p-3 rounded-lg bg-white dark:bg-[#111A17] border border-[#D9E1D7] dark:border-[#26362E] text-xs">
          <div className="font-semibold text-[#147C68] dark:text-[#6CD8C0] flex items-center gap-1.5 mb-1">
            <ShieldCheck className="w-3.5 h-3.5" /> MANAGER
          </div>
          <p className="text-[#657369] dark:text-[#9AA99E] text-[11px] leading-relaxed">
            Operational oversight, inventory adjustments, stock transfers, and warehouse routing.
          </p>
        </div>
        <div className="p-3 rounded-lg bg-white dark:bg-[#111A17] border border-[#D9E1D7] dark:border-[#26362E] text-xs">
          <div className="font-semibold text-[#456B1F] dark:text-[#C6F36B] flex items-center gap-1.5 mb-1">
            <ShieldCheck className="w-3.5 h-3.5" /> OPERATOR
          </div>
          <p className="text-[#657369] dark:text-[#9AA99E] text-[11px] leading-relaxed">
            Daily operations: receiving, order fulfillment, stock reservations, and discrepancy triage.
          </p>
        </div>
        <div className="p-3 rounded-lg bg-white dark:bg-[#111A17] border border-[#D9E1D7] dark:border-[#26362E] text-xs">
          <div className="font-semibold text-[#657369] dark:text-[#9AA99E] flex items-center gap-1.5 mb-1">
            <ShieldCheck className="w-3.5 h-3.5" /> VIEWER
          </div>
          <p className="text-[#657369] dark:text-[#9AA99E] text-[11px] leading-relaxed">
            Read-only access to catalog, current inventory balances, and executive dashboard metrics.
          </p>
        </div>
      </div>

      {/* Filter Toolbar */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 p-3.5 bg-white dark:bg-[#111A17] rounded-xl border border-[#D9E1D7] dark:border-[#26362E] shadow-sm">
        <div className="flex flex-col sm:flex-row sm:items-center gap-3 flex-1">
          {/* Search by User ID */}
          <div className="relative flex-1 max-w-sm">
            <Search className="w-3.5 h-3.5 absolute left-3 top-1/2 -translate-y-1/2 text-[#8A988E] dark:text-[#6C7D71] pointer-events-none" />
            <input
              type="text"
              aria-label="Search members by user ID"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              placeholder="Search by User ID / Principal..."
              className="w-full pl-8 pr-8 py-1.5 text-xs rounded-md bg-[#E9EEE6]/50 dark:bg-[#1B2922]/50 border border-[#D9E1D7] dark:border-[#26362E] text-[#18231D] dark:text-[#EEF3EA] placeholder-[#8A988E] dark:placeholder-[#6C7D71] focus:outline-none focus:border-[#456B1F] dark:focus:border-[#C6F36B] font-mono caret-[#18231D] dark:caret-[#EEF3EA]"
            />
            {searchQuery && (
              <button
                type="button"
                onClick={() => setSearchQuery('')}
                className="absolute right-2.5 top-1/2 -translate-y-1/2 text-[#8A988E] hover:text-[#18231D] dark:hover:text-[#EEF3EA]"
                aria-label="Clear member search"
              >
                <X className="w-3.5 h-3.5" />
              </button>
            )}
          </div>

          {/* Role Filter */}
          <div className="w-full sm:w-44">
            <select
              aria-label="Filter by role"
              value={roleFilter}
              onChange={(e) => setRoleFilter(e.target.value as 'ALL' | Role)}
              className="w-full py-1.5 px-2.5 text-xs rounded-md bg-[#E9EEE6]/50 dark:bg-[#1B2922]/50 border border-[#D9E1D7] dark:border-[#26362E] text-[#18231D] dark:text-[#EEF3EA] focus:outline-none focus:border-[#456B1F] dark:focus:border-[#C6F36B]"
            >
              <option value="ALL">All Roles</option>
              <option value="ADMIN">ADMIN</option>
              <option value="MANAGER">MANAGER</option>
              <option value="OPERATOR">OPERATOR</option>
              <option value="VIEWER">VIEWER</option>
            </select>
          </div>

          {/* Status Filter */}
          <div className="w-full sm:w-36">
            <select
              aria-label="Filter by status"
              value={statusFilter}
              onChange={(e) => setStatusFilter(e.target.value as 'ALL' | 'ACTIVE' | 'INACTIVE')}
              className="w-full py-1.5 px-2.5 text-xs rounded-md bg-[#E9EEE6]/50 dark:bg-[#1B2922]/50 border border-[#D9E1D7] dark:border-[#26362E] text-[#18231D] dark:text-[#EEF3EA] focus:outline-none focus:border-[#456B1F] dark:focus:border-[#C6F36B]"
            >
              <option value="ALL">All Statuses</option>
              <option value="ACTIVE">Active Only</option>
              <option value="INACTIVE">Inactive Only</option>
            </select>
          </div>
        </div>

        <div className="flex items-center gap-3 text-xs">
          <span className="text-[#657369] dark:text-[#9AA99E] font-mono">
            Showing {filteredMembers.length} of {members.length} member(s)
          </span>
          {(searchQuery || roleFilter !== 'ALL' || statusFilter !== 'ALL') && (
            <button
              type="button"
              onClick={handleResetFilters}
              className="inline-flex items-center gap-1 text-[#456B1F] dark:text-[#C6F36B] hover:underline font-medium cursor-pointer"
            >
              <RotateCcw className="w-3 h-3" />
              Reset Filters
            </button>
          )}
        </div>
      </div>

      {/* Members Table */}
      <Card className="border-[#D9E1D7] dark:border-[#26362E] overflow-hidden shadow-sm">
        {loading && members.length === 0 ? (
          <TableSkeleton rows={5} cols={5} />
        ) : members.length === 0 ? (
          <EmptyState
            icon={<Users className="w-8 h-8 text-[#456B1F] dark:text-[#C6F36B]" />}
            title="No members registered"
            description="No active memberships found for the current organization context."
          />
        ) : filteredMembers.length === 0 ? (
          <EmptyState
            icon={<Users className="w-8 h-8 text-[#456B1F] dark:text-[#C6F36B]" />}
            title="No matching members"
            description="No members match the selected search query or role filter."
            actionLabel="Reset Filters"
            onAction={handleResetFilters}
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-xs border-collapse">
              <thead>
                <tr className="border-b border-[#D9E1D7] dark:border-[#26362E] bg-[#E9EEE6]/60 dark:bg-[#1B2922]/60 text-[#657369] dark:text-[#9AA99E] uppercase font-mono tracking-wider">
                  <th className="py-3 px-4">User ID / Principal</th>
                  <th className="py-3 px-4">Role</th>
                  <th className="py-3 px-4">Status</th>
                  <th className="py-3 px-4">Joined</th>
                  <th className="py-3 px-4 text-right">Actions</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-[#D9E1D7] dark:divide-[#26362E] text-[#18231D] dark:text-[#EEF3EA]">
                {filteredMembers.map((m) => {
                  const isCurrentUser = user?.id === m.userId;
                  return (
                    <tr key={m.id} className="hover:bg-[#E9EEE6]/50 dark:hover:bg-[#1B2922]/50 transition-colors group">
                      <td className="py-3 px-4 font-mono text-[#657369] dark:text-[#9AA99E]">
                        <div className="flex items-center gap-2">
                          <span className="font-medium text-[#18231D] dark:text-[#EEF3EA]">{m.userId}</span>
                          {isCurrentUser && (
                            <Badge variant="info" className="text-[10px] py-0 px-1.5">
                              You
                            </Badge>
                          )}
                          <button
                            onClick={() => copyToClipboard(m.userId, `user-${m.id}`)}
                            className="opacity-0 group-hover:opacity-100 p-1 hover:bg-[#E9EEE6] dark:hover:bg-[#1B2922] rounded text-[#8A988E] hover:text-[#18231D] dark:hover:text-[#EEF3EA] transition-opacity cursor-pointer"
                            title="Copy User ID"
                          >
                            {copiedKey === `user-${m.id}` ? (
                              <Check className="w-3 h-3 text-[#456B1F] dark:text-[#C6F36B]" />
                            ) : (
                              <Copy className="w-3 h-3" />
                            )}
                          </button>
                        </div>
                      </td>
                      <td className="py-3 px-4">
                        <Badge variant={getRoleBadgeVariant(m.role)} className="font-mono text-xs">
                          {m.role}
                        </Badge>
                      </td>
                      <td className="py-3 px-4">
                        {m.active ? (
                          <span className="inline-flex items-center gap-1.5 text-[#456B1F] dark:text-[#C6F36B] text-xs">
                            <span className="w-1.5 h-1.5 rounded-full bg-[#456B1F] dark:bg-[#C6F36B]" />
                            Active
                          </span>
                        ) : (
                          <span className="inline-flex items-center gap-1.5 text-[#8A988E] dark:text-[#6C7D71] text-xs">
                            <span className="w-1.5 h-1.5 rounded-full bg-[#8A988E] dark:bg-[#6C7D71]" />
                            Inactive
                          </span>
                        )}
                      </td>
                      <td className="py-3 px-4 font-mono text-[#8A988E] dark:text-[#6C7D71]">
                        {formatTimestamp(m.createdAt)}
                      </td>
                      <td className="py-3 px-4 text-right">
                        {canManageUsers && !isCurrentUser ? (
                          <div className="flex items-center justify-end gap-1.5">
                            <Button
                              variant="ghost"
                              size="sm"
                              onClick={() => {
                                setEditingMember(m);
                                setEditRole(m.role);
                                setEditError(null);
                              }}
                              icon={<Edit3 className="w-3.5 h-3.5" />}
                              className="text-[#657369] dark:text-[#9AA99E] hover:text-[#18231D] dark:hover:text-[#EEF3EA] h-7 px-2"
                            >
                              Role
                            </Button>
                            {m.active && (
                              <Button
                                variant="ghost"
                                size="sm"
                                onClick={() => setDeactivatingMember(m)}
                                icon={<UserX className="w-3.5 h-3.5" />}
                                className="text-rose-600 dark:text-rose-400/80 hover:text-rose-700 dark:hover:text-rose-300 hover:bg-rose-50 dark:hover:bg-rose-500/10 h-7 px-2"
                              >
                                Revoke
                              </Button>
                            )}
                          </div>
                        ) : (
                          <span className="text-[#8A988E] dark:text-[#6C7D71] text-xs font-mono">—</span>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      {/* Add Member Modal */}
      <Modal
        isOpen={isAddOpen}
        onClose={() => setIsAddOpen(false)}
        title="Grant Organization Access"
        size="md"
      >
        <form onSubmit={handleAddMember} className="space-y-4">
          {addError && (
            <div className="p-3 rounded bg-rose-500/10 border border-rose-500/20 text-rose-600 dark:text-rose-400 text-xs">
              {addError}
            </div>
          )}

          <div>
            <label className="block text-xs font-medium text-[#18231D] dark:text-[#EEF3EA] mb-1">
              User UUID <span className="text-rose-500 dark:text-rose-400">*</span>
            </label>
            <Input
              value={addUserId}
              onChange={(e) => setAddUserId(e.target.value)}
              placeholder="e.g. 550e8400-e29b-41d4-a716-446655440000"
              required
              className="font-mono text-xs"
            />
            <p className="text-[11px] text-[#657369] dark:text-[#9AA99E] mt-1">
              Existing platform user UUID to bind to {currentOrg?.name || 'this organization'}.
            </p>
          </div>

          <div>
            <label className="block text-xs font-medium text-[#18231D] dark:text-[#EEF3EA] mb-1">
              Assigned Role <span className="text-rose-500 dark:text-rose-400">*</span>
            </label>
            <Select
              value={addRole}
              onChange={(e) => setAddRole(e.target.value as Role)}
              options={ROLE_OPTIONS}
            />
          </div>

          <div className="p-3 rounded bg-[#E9EEE6]/50 dark:bg-[#1B2922]/50 border border-[#D9E1D7] dark:border-[#26362E] text-xs text-[#657369] dark:text-[#9AA99E] space-y-1">
            <span className="font-semibold text-[#18231D] dark:text-[#EEF3EA] flex items-center gap-1">
              <Info className="w-3 h-3 text-[#147C68] dark:text-[#6CD8C0]" /> Organization Isolation Note
            </span>
            <p className="text-[11px] leading-relaxed">
              Users granted access can only inspect and mutate data strictly belonging to this tenant context.
            </p>
          </div>

          <div className="flex justify-end gap-2 pt-2 border-t border-[#D9E1D7] dark:border-[#26362E]">
            <Button variant="secondary" onClick={() => setIsAddOpen(false)} disabled={addLoading}>
              Cancel
            </Button>
            <Button variant="primary" type="submit" loading={addLoading}>
              Grant Access
            </Button>
          </div>
        </form>
      </Modal>

      {/* Edit Role Modal */}
      {editingMember && (
        <Modal
          isOpen={!!editingMember}
          onClose={() => setEditingMember(null)}
          title="Update Member Role"
          size="md"
        >
          <form onSubmit={handleUpdateRole} className="space-y-4">
            {editError && (
              <div className="p-3 rounded bg-rose-500/10 border border-rose-500/20 text-rose-600 dark:text-rose-400 text-xs">
                {editError}
              </div>
            )}

            <div>
              <label className="block text-xs font-medium text-[#657369] dark:text-[#9AA99E] mb-1">User ID</label>
              <div className="p-2.5 rounded bg-[#E9EEE6]/50 dark:bg-[#1B2922]/50 border border-[#D9E1D7] dark:border-[#26362E] font-mono text-xs text-[#18231D] dark:text-[#EEF3EA] select-all">
                {editingMember.userId}
              </div>
            </div>

            <div>
              <label className="block text-xs font-medium text-[#18231D] dark:text-[#EEF3EA] mb-1">
                New Role Assignment <span className="text-rose-500 dark:text-rose-400">*</span>
              </label>
              <Select
                value={editRole}
                onChange={(e) => setEditRole(e.target.value as Role)}
                options={ROLE_OPTIONS}
              />
            </div>

            <div className="flex justify-end gap-2 pt-2 border-t border-[#D9E1D7] dark:border-[#26362E]">
              <Button variant="secondary" onClick={() => setEditingMember(null)} disabled={editLoading}>
                Cancel
              </Button>
              <Button variant="primary" type="submit" loading={editLoading}>
                Save Changes
              </Button>
            </div>
          </form>
        </Modal>
      )}

      {/* Deactivate Confirm Dialog */}
      <ConfirmDialog
        isOpen={!!deactivatingMember}
        onClose={() => setDeactivatingMember(null)}
        onConfirm={handleDeactivate}
        title="Revoke Organization Access"
        message={`Are you sure you want to revoke organization access for user ${deactivatingMember?.userId}? They will immediately lose all operational privileges within this tenant context.`}
        confirmText="Revoke Access"
        variant="danger"
        isLoading={deactivateLoading}
      />
    </div>
  );
};

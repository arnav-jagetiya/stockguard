import type { Role } from './auth';

export interface OrganizationMember {
  id: string;
  organizationId: string;
  userId: string;
  role: Role;
  active: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface AddMemberRequest {
  userId: string;
  role: Role;
}

export interface UpdateMemberRoleRequest {
  role: Role;
}

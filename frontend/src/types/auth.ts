export type Role = 'ADMIN' | 'MANAGER' | 'OPERATOR' | 'VIEWER';

export interface UserSummary {
  id: string;
  email: string;
  fullName: string;
  active: boolean;
}

export interface OrganizationSummary {
  id: string;
  name: string;
  role: Role;
}

export interface OrganizationMembershipSummary {
  organizationId: string;
  organizationName: string;
  role: Role;
}

export interface UserMeResponse {
  id: string;
  email: string;
  fullName: string;
  active: boolean;
  memberships: OrganizationMembershipSummary[];
}

export interface AuthTokenResponse {
  accessToken: string;
  tokenType: string;
  expiresInSeconds: number;
  user: UserSummary;
  organizations: OrganizationSummary[];
}

export interface SwitchOrgResponse {
  organizationId: string;
  organizationName: string;
  role: Role;
  permissions: string[];
}

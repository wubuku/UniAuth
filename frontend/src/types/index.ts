// TypeScript类型定义

export interface User {
  id?: number | string;
  username?: string;
  email?: string;
  displayName?: string;
  avatarUrl?: string;
  provider: 'google' | 'github' | 'x' | 'local' | 'web3' | 'unknown';
  authenticated?: boolean;
  userName?: string;
  userEmail?: string;
  userId?: string;
  userAvatar?: string;
  providerInfo?: {
    sub?: string;
    htmlUrl?: string;
    publicRepos?: number;
    followers?: number;
    location?: string;
    verified?: boolean;
    description?: string;
  };
}

export interface ApiResponse<T> {
  data?: T;
  error?: string;
}

export interface TokenValidationResult {
  valid: boolean;
  user?: any;
  error?: string;
}

export interface LoginProvider {
  name: 'google' | 'github' | 'x';  // ✅ X API v2：提供者名改为 'x'
  displayName: string;
  color: string;
  icon: string;
}

export interface TokenRefreshResult {
  message: string;
  accessToken: string;
  refreshToken: string;
  accessTokenExpiresIn: number;
  refreshTokenExpiresIn: number;
  tokenType?: string;
}

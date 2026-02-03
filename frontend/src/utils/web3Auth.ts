import { BrowserProvider } from 'ethers';

const API_BASE_URL = (import.meta as any)?.env?.VITE_API_BASE_URL || 'http://localhost:8081';

interface EthereumProvider {
  isMetaMask?: boolean;
  request: (args: { method: string; params?: unknown[] }) => Promise<unknown>;
  on: (event: string, handler: (...args: unknown[]) => void) => void;
  removeListener: (event: string, handler: (...args: unknown[]) => void) => void;
}

declare global {
  interface Window {
    ethereum?: EthereumProvider;
  }
}

export interface NonceResponse {
  nonce: string;
  message: string;
}

export interface Web3LoginRequest {
  walletAddress: string;
  message: string;
  signature: string;
  nonce: string;
}

export interface AuthResponse {
  accessToken: string;
  refreshToken: string;
  tokenType: string;
  expiresIn: number;
  walletAddress: string;
}

class Web3Auth {
  private provider: BrowserProvider | null = null;
  private signer: unknown = null;

  isMetaMaskInstalled(): boolean {
    return typeof window.ethereum !== 'undefined';
  }

  async connectWallet(): Promise<string> {
    if (!this.isMetaMaskInstalled()) {
      throw new Error('请先安装 MetaMask 钱包插件');
    }

    try {
      this.provider = new BrowserProvider(window.ethereum as EthereumProvider);
      const accounts = await this.provider.send('eth_requestAccounts', []);

      if (accounts.length === 0) {
        throw new Error('未检测到钱包账户');
      }

      this.signer = await this.provider.getSigner();
      const walletAddress = await (this.signer as { getAddress(): Promise<string> }).getAddress();

      console.log('✅ 钱包连接成功:', walletAddress);
      return walletAddress;
    } catch (error: unknown) {
      console.error('❌ 连接钱包失败:', error);
      throw error;
    }
  }

  async login(): Promise<AuthResponse> {
    try {
      const walletAddress = await this.connectWallet();

      const { nonce, message } = await this.getNonce(walletAddress);

      const signature = await this.signMessage(message);

      const authData = await this.verifySignature({
        walletAddress,
        message,
        signature,
        nonce
      });

      this.saveAuthData(authData);

      console.log('✅ Web3 登录成功!');
      return authData;
    } catch (error: unknown) {
      console.error('❌ Web3 登录失败:', error);
      throw error;
    }
  }

  async getNonce(walletAddress: string): Promise<NonceResponse> {
    const response = await fetch(
      `${API_BASE_URL}/api/auth/web3/nonce/${walletAddress}`
    );

    if (!response.ok) {
      const error = await response.text();
      throw new Error(`获取 nonce 失败: ${error}`);
    }

    const data = await response.json();
    console.log('📝 获取 nonce:', data.nonce);
    return data;
  }

  async signMessage(message: string): Promise<string> {
    if (!this.signer) {
      throw new Error('请先连接钱包');
    }

    console.log('✍️ 请在 MetaMask 中签名...');
    const signature = await (this.signer as { signMessage(message: string): Promise<string> }).signMessage(message);
    console.log('✅ 签名完成');
    return signature;
  }

  async verifySignature(loginData: Web3LoginRequest): Promise<AuthResponse> {
    const response = await fetch(
      `${API_BASE_URL}/api/auth/web3/verify`,
      {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json'
        },
        body: JSON.stringify(loginData)
      }
    );

    if (!response.ok) {
      const error = await response.text();
      throw new Error(error || '验证签名失败');
    }

    return await response.json();
  }

  saveAuthData(authData: AuthResponse): void {
    localStorage.setItem('accessToken', authData.accessToken);
    localStorage.setItem('refreshToken', authData.refreshToken);
    localStorage.setItem('walletAddress', authData.walletAddress);
    localStorage.setItem('authProvider', 'WEB3');
  }

  getAccessToken(): string | null {
    return localStorage.getItem('accessToken');
  }

  getWalletAddress(): string | null {
    return localStorage.getItem('walletAddress');
  }

  isAuthenticated(): boolean {
    return !!this.getAccessToken();
  }

  logout(): void {
    localStorage.removeItem('accessToken');
    localStorage.removeItem('refreshToken');
    localStorage.removeItem('walletAddress');
    localStorage.removeItem('authProvider');
    console.log('✅ 已登出');
  }
}

export const web3Auth = new Web3Auth();
export default web3Auth;

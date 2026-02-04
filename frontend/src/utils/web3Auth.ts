import { BrowserProvider } from 'ethers';

export class Web3Auth {
  /**
   * Check if Ethereum wallet (e.g. MetaMask) is installed
   */
  static isWalletInstalled(): boolean {
    return typeof window !== 'undefined' && typeof (window as any).ethereum !== 'undefined';
  }

  /**
   * Request wallet connection and get accounts
   */
  static async connectWallet(): Promise<string> {
    if (!this.isWalletInstalled()) {
      throw new Error('请先安装MetaMask或其他Web3钱包插件');
    }

    try {
      const provider = new BrowserProvider((window as any).ethereum);
      // Request account access
      const accounts = await provider.send("eth_requestAccounts", []);
      
      if (!accounts || accounts.length === 0) {
        throw new Error('未找到钱包账户');
      }
      
      return accounts[0];
    } catch (error: any) {
      if (error.code === 4001) {
        throw new Error('用户拒绝了连接请求');
      }
      throw error;
    }
  }

  /**
   * Sign a message using the wallet
   */
  static async signMessage(message: string): Promise<string> {
    if (!this.isWalletInstalled()) {
      throw new Error('未找到Web3钱包');
    }

    try {
      const provider = new BrowserProvider((window as any).ethereum);
      const signer = await provider.getSigner();
      return await signer.signMessage(message);
    } catch (error: any) {
      if (error.code === 4001) {
        throw new Error('用户拒绝了签名请求');
      }
      throw error;
    }
  }

  /**
   * Listen for account changes
   */
  static onAccountsChanged(callback: (accounts: string[]) => void): void {
    if (this.isWalletInstalled()) {
      (window as any).ethereum.on('accountsChanged', callback);
    }
  }

  /**
   * Remove account change listener
   */
  static removeAccountsChangedListener(callback: (accounts: string[]) => void): void {
    if (this.isWalletInstalled()) {
      (window as any).ethereum.removeListener('accountsChanged', callback);
    }
  }
}

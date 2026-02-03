import React, { useState, useCallback } from 'react';
import web3Auth, { AuthResponse } from '../utils/web3Auth';

interface Web3LoginButtonProps {
  onLoginSuccess?: (authData: AuthResponse) => void;
  onLoginError?: (error: Error) => void;
}

const Web3LoginButton: React.FC<Web3LoginButtonProps> = ({
  onLoginSuccess,
  onLoginError
}) => {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [walletAddress, setWalletAddress] = useState('');
  const [isConnected, setIsConnected] = useState(false);

  const formatAddress = (address: string): string => {
    if (!address) return '';
    return `${address.substring(0, 6)}...${address.substring(address.length - 4)}`;
  };

  const handleLogin = useCallback(async () => {
    setLoading(true);
    setError('');

    try {
      const authData = await web3Auth.login();
      setWalletAddress(authData.walletAddress);
      setIsConnected(true);
      onLoginSuccess?.(authData);
      window.location.href = '/';
    } catch (err: any) {
      setError(err.message || '登录失败');
      console.error(err);
      onLoginError?.(err);
    } finally {
      setLoading(false);
    }
  }, [onLoginSuccess, onLoginError]);

  const handleLogout = useCallback(() => {
    web3Auth.logout();
    setWalletAddress('');
    setIsConnected(false);
  }, []);

  if (isConnected) {
    return (
      <div className="p-4 border border-gray-200 rounded-lg">
        <div className="flex items-center gap-3 mb-4">
          <div className="w-10 h-10 bg-gradient-to-r from-orange-500 to-blue-500 rounded-full flex items-center justify-center">
            <svg className="w-6 h-6 text-white" viewBox="0 0 24 24" fill="currentColor">
              <path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-1 17.93c-3.95-.49-7-3.85-7-7.93 0-.62.08-1.21.21-1.79L9 15v1c0 1.1.9 2 2 2v1.93zm6.9-2.54c-.26-.81-1-1.39-1.9-1.39h-1v-3c0-.55-.45-1-1-1H8v-2h2c.55 0 1-.45 1-1V7h2c1.1 0 2-.9 2-2v-.41c2.93 1.19 5 4.06 5 7.41 0 2.08-.8 3.97-2.1 5.39z"/>
            </svg>
          </div>
          <div>
            <p className="text-sm text-gray-500">Web3 钱包已连接</p>
            <p className="font-mono font-medium">{formatAddress(walletAddress)}</p>
          </div>
        </div>
        <button
          onClick={handleLogout}
          className="w-full py-2 px-4 bg-red-500 hover:bg-red-600 text-white rounded-lg transition-colors"
        >
          断开连接
        </button>
      </div>
    );
  }

  return (
    <div className="p-4 border border-gray-200 rounded-lg">
      <h3 className="text-lg font-medium mb-4">Web3 钱包登录</h3>

      {error && (
        <div className="mb-4 p-3 bg-red-50 border border-red-200 text-red-600 text-sm rounded-lg">
          ❌ {error}
        </div>
      )}

      <button
        onClick={handleLogin}
        disabled={loading}
        className={`w-full py-3 px-4 bg-gradient-to-r from-orange-500 to-blue-500 hover:from-orange-600 hover:to-blue-600 text-white rounded-lg transition-all flex items-center justify-center gap-2 ${
          loading ? 'opacity-50 cursor-not-allowed' : ''
        }`}
      >
        {loading ? (
          <>
            <svg className="animate-spin h-5 w-5" viewBox="0 0 24 24">
              <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" fill="none" />
              <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z" />
            </svg>
            <span>连接中...</span>
          </>
        ) : (
          <>
            <svg className="w-5 h-5" viewBox="0 0 24 24" fill="currentColor">
              <path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-1 17.93c-3.95-.49-7-3.85-7-7.93 0-.62.08-1.21.21-1.79L9 15v1c0 1.1.9 2 2 2v1.93zm6.9-2.54c-.26-.81-1-1.39-1.9-1.39h-1v-3c0-.55-.45-1-1-1H8v-2h2c.55 0 1-.45 1-1V7h2c1.1 0 2-.9 2-2v-.41c2.93 1.19 5 4.06 5 7.41 0 2.08-.8 3.97-2.1 5.39z"/>
            </svg>
            <span>连接钱包</span>
          </>
        )}
      </button>

      <div className="mt-4 text-xs text-gray-500">
        <p>💡 提示:</p>
        <ul className="list-disc list-inside mt-1 space-y-1">
          <li>支持 MetaMask、Coinbase Wallet 等</li>
          <li>签名不会消耗 Gas 费用</li>
          <li>首次登录将自动创建账户</li>
        </ul>
      </div>
    </div>
  );
};

export default Web3LoginButton;

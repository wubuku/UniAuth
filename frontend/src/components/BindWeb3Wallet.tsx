import { useState, useCallback } from 'react';
import web3Auth from '../utils/web3Auth';

interface BindWeb3WalletProps {
  onBindSuccess?: () => void;
  onBindError?: (error: Error) => void;
}

const BindWeb3Wallet: React.FC<BindWeb3WalletProps> = ({
  onBindSuccess,
  onBindError
}) => {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [walletAddress, setWalletAddress] = useState('');
  const [isConnected, setIsConnected] = useState(false);
  const [bindSuccess, setBindSuccess] = useState(false);

  const checkWalletStatus = useCallback(async () => {
    try {
      const address = await web3Auth.connectWallet();
      setWalletAddress(address);
      setIsConnected(true);
      return address;
    } catch (err: any) {
      setError(err.message || '连接钱包失败');
      return null;
    }
  }, []);

  const handleBind = useCallback(async () => {
    setLoading(true);
    setError('');
    setBindSuccess(false);

    try {
      const address = await checkWalletStatus();
      if (!address) {
        throw new Error('无法连接钱包');
      }

      const { nonce, message } = await web3Auth.getNonce(address);
      const signature = await web3Auth.signMessage(message);

      const response = await fetch('/api/auth/web3/bind', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${localStorage.getItem('accessToken') || ''}`
        },
        body: JSON.stringify({
          walletAddress: address,
          message,
          signature,
          nonce
        })
      });

      if (!response.ok) {
        const errorText = await response.text();
        throw new Error(errorText || '绑定失败');
      }

      setBindSuccess(true);
      setWalletAddress(address);
      onBindSuccess?.();
    } catch (err: any) {
      setError(err.message || '绑定失败');
      onBindError?.(err);
    } finally {
      setLoading(false);
    }
  }, [checkWalletStatus, onBindSuccess, onBindError]);

  const handleDisconnect = useCallback(() => {
    web3Auth.logout();
    setWalletAddress('');
    setIsConnected(false);
    setBindSuccess(false);
  }, []);

  if (bindSuccess) {
    return (
      <div style={{
        marginBottom: '15px',
        padding: '15px',
        backgroundColor: '#d4edda',
        borderRadius: '8px',
        border: '1px solid #c3e6cb'
      }}>
        <h4 style={{ margin: '0 0 12px 0', color: '#155724' }}>✅ Web3 钱包绑定成功</h4>
        <p style={{ margin: 0, color: '#155724', fontSize: '14px', fontFamily: 'monospace' }}>
          {walletAddress}
        </p>
        <button
          onClick={handleDisconnect}
          style={{
            marginTop: '12px',
            padding: '8px 16px',
            backgroundColor: '#6c757d',
            color: 'white',
            border: 'none',
            borderRadius: '5px',
            fontSize: '14px',
            cursor: 'pointer'
          }}
        >
          断开钱包
        </button>
      </div>
    );
  }

  return (
    <div style={{
      marginBottom: '15px',
      padding: '15px',
      backgroundColor: '#f8f9fa',
      borderRadius: '8px',
      border: '1px solid #dee2e6'
    }}>
      <h4 style={{ margin: '0 0 12px 0', color: '#333' }}>🔗 绑定 Web3 钱包</h4>
      <p style={{ color: '#666', fontSize: '14px', marginBottom: '12px' }}>
        绑定以太坊钱包到您的账户，支持 MetaMask、Coinbase Wallet 等
      </p>

      {error && (
        <div style={{
          padding: '10px',
          backgroundColor: '#f8d7da',
          color: '#721c24',
          borderRadius: '5px',
          marginBottom: '12px',
          fontSize: '14px'
        }}>
          ❌ {error}
        </div>
      )}

      {isConnected ? (
        <div style={{
          padding: '12px',
          backgroundColor: '#e7f3ff',
          borderRadius: '5px',
          marginBottom: '12px'
        }}>
          <div style={{ marginBottom: '8px', color: '#004085', fontSize: '14px' }}>
            已连接钱包：
          </div>
          <div style={{
            fontFamily: 'monospace',
            fontSize: '14px',
            wordBreak: 'break-all',
            color: '#0056b3'
          }}>
            {walletAddress}
          </div>
        </div>
      ) : null}

      <button
        onClick={isConnected ? handleBind : checkWalletStatus}
        disabled={loading}
        style={{
          padding: '10px 20px',
          backgroundColor: loading ? '#6c757d' : 'linear-gradient(to right, #f7931a, #627eea)',
          color: 'white',
          border: 'none',
          borderRadius: '5px',
          fontSize: '14px',
          fontWeight: 'bold',
          cursor: loading ? 'not-allowed' : 'pointer',
          opacity: loading ? 0.6 : 1,
          transition: 'all 0.3s'
        }}
      >
        {loading ? '处理中...' : isConnected ? '确认绑定' : '连接并绑定钱包'}
      </button>
    </div>
  );
};

export default BindWeb3Wallet;

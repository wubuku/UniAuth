import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Web3Auth } from '../utils/web3Auth';
import { AuthService } from '../services/authService';

interface Web3LoginButtonProps {
  onError?: (message: string) => void;
  onLogin: (data: {
    walletAddress: string;
    message: string;
    signature: string;
    nonce: string;
  }) => Promise<unknown>;
}

export default function Web3LoginButton({ onError, onLogin }: Web3LoginButtonProps) {
  const [loading, setLoading] = useState(false);
  const navigate = useNavigate();

  const handleLogin = async () => {
    try {
      setLoading(true);
      
      // 1. 连接钱包
      const address = await Web3Auth.connectWallet();
      console.log('Wallet connected:', address);

      // 2. 获取Nonce
      const { nonce, message } = await AuthService.getWeb3Nonce(address);
      console.log('Nonce received:', nonce);

      // 3. 签名消息
      const signature = await Web3Auth.signMessage(message);
      console.log('Message signed');

      // 4. 执行登录
      await onLogin({
        walletAddress: address,
        message,
        signature,
        nonce
      });

      // 5. 登录成功后跳转到首页
      navigate('/');
      
    } catch (error: any) {
      console.error('Web3 login process failed:', error);
      if (onError) {
        onError(error.message || 'Web3登录失败');
      }
    } finally {
      setLoading(false);
    }
  };

  return (
    <button
      onClick={handleLogin}
      disabled={loading}
      style={{
        backgroundColor: '#F6851B', // Ethereum Orange
        color: 'white',
        border: 'none',
        padding: '12px 15px',
        borderRadius: '8px',
        fontSize: '14px',
        fontWeight: 'bold',
        cursor: loading ? 'not-allowed' : 'pointer',
        transition: 'all 0.3s ease',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        gap: '8px',
        opacity: loading ? 0.7 : 1
      }}
      onMouseOver={(e) => {
        if (!loading) {
          e.currentTarget.style.backgroundColor = '#e2761b';
          e.currentTarget.style.transform = 'translateY(-1px)';
        }
      }}
      onMouseOut={(e) => {
        if (!loading) {
          e.currentTarget.style.backgroundColor = '#F6851B';
          e.currentTarget.style.transform = 'translateY(0)';
        }
      }}
    >
      <span style={{ fontSize: '16px' }}>🦊</span>
      {loading ? '连接钱包中...' : '使用 Web3 钱包登录'}
    </button>
  );
}

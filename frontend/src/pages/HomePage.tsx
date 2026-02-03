import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { useAuth } from '../hooks/useAuth';

interface TokenInfo {
  accessToken: string;
  expiresIn: number;
  expiresAt: number;
}

export default function HomePage() {
  const { isAuthenticated, user } = useAuth();
  const [tokenInfo, setTokenInfo] = useState<TokenInfo | null>(null);
  const [timeRemaining, setTimeRemaining] = useState<string>('');

  useEffect(() => {
    if (isAuthenticated) {
      const accessToken = localStorage.getItem('accessToken');
      if (accessToken) {
        try {
          const payload = JSON.parse(atob(accessToken.split('.')[1]));
          const expiresAt = payload.exp * 1000;
          setTokenInfo({
            accessToken: accessToken,
            expiresIn: Math.max(0, Math.floor((expiresAt - Date.now()) / 1000)),
            expiresAt
          });
        } catch (e) {
          console.error('Failed to parse token:', e);
        }
      }
    }
  }, [isAuthenticated]);

  useEffect(() => {
    if (!tokenInfo?.expiresAt) return;

    const updateTimeRemaining = () => {
      const remaining = Math.max(0, Math.floor((tokenInfo.expiresAt - Date.now()) / 1000));
      const hours = Math.floor(remaining / 3600);
      const minutes = Math.floor((remaining % 3600) / 60);
      const seconds = remaining % 60;
      setTimeRemaining(`${hours}h ${minutes}m ${seconds}s`);
    };

    updateTimeRemaining();
    const interval = setInterval(updateTimeRemaining, 1000);
    return () => clearInterval(interval);
  }, [tokenInfo?.expiresAt]);

  const getProviderDisplayName = (provider: string): string => {
    const names: Record<string, string> = {
      google: 'Google',
      github: 'GitHub',
      x: 'Twitter/X',
      local: '本地账号',
      web3: 'Web3 钱包'
    };
    return names[provider] || provider;
  };

  const formatAddress = (address: string): string => {
    if (!address) return '';
    return `${address.substring(0, 6)}...${address.substring(address.length - 4)}`;
  };

  return (
    <div style={{
      maxWidth: '800px',
      margin: '0 auto',
      padding: '20px',
      fontFamily: 'Arial, sans-serif'
    }}>
      <div style={{
        background: 'linear-gradient(135deg, #667eea 0%, #764ba2 100%)',
        color: 'white',
        padding: '12px',
        borderRadius: '8px',
        marginBottom: '20px',
        fontSize: '16px',
        fontWeight: 'bold',
        textAlign: 'center'
      }}>
        🚀 当前使用：React 前端实现 (Modern SPA)
      </div>

      <h1 style={{
        color: '#333',
        textAlign: 'center',
        marginBottom: '30px'
      }}>
        React OAuth2 + Web3 登录演示
      </h1>

      {isAuthenticated && user ? (
        <div style={{
          background: 'white',
          padding: '30px',
          borderRadius: '10px',
          boxShadow: '0 2px 10px rgba(0,0,0,0.1)',
          marginBottom: '20px'
        }}>
          <div style={{
            display: 'flex',
            alignItems: 'center',
            gap: '15px',
            marginBottom: '20px',
            paddingBottom: '20px',
            borderBottom: '1px solid #eee'
          }}>
            <div style={{
              width: '60px',
              height: '60px',
              borderRadius: '50%',
              background: user.provider === 'web3' 
                ? 'linear-gradient(135deg, #f7931a 0%, #627eea 100%)'
                : '#007bff',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              fontSize: '24px',
              color: 'white'
            }}>
              {user.provider === 'web3' ? '🌐' : '👤'}
            </div>
            <div>
              <div style={{ fontSize: '18px', fontWeight: 'bold', color: '#333' }}>
                {user.provider === 'web3' ? formatAddress(user.userName || '') : (user.userName || user.displayName)}
              </div>
              <div style={{ color: '#666', fontSize: '14px' }}>
                登录方式：{getProviderDisplayName(user.provider)}
              </div>
              {user.userId && (
                <div style={{ color: '#999', fontSize: '12px', fontFamily: 'monospace' }}>
                  ID: {user.userId}
                </div>
              )}
            </div>
          </div>

          {timeRemaining && (
            <div style={{
              padding: '15px',
              background: '#f8f9fa',
              borderRadius: '8px',
              marginBottom: '20px'
            }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                <span style={{ color: '#666' }}>Access Token 剩余有效期：</span>
                <span style={{ 
                  fontWeight: 'bold', 
                  color: parseInt(timeRemaining) < 300 ? '#dc3545' : '#28a745',
                  fontFamily: 'monospace'
                }}>
                  {timeRemaining}
                </span>
              </div>
            </div>
          )}

          <div style={{ display: 'flex', gap: '12px', flexWrap: 'wrap' }}>
            <Link
              to="/test"
              style={{
                display: 'inline-block',
                backgroundColor: '#007bff',
                color: 'white',
                padding: '12px 30px',
                textDecoration: 'none',
                borderRadius: '5px',
                fontSize: '16px',
                fontWeight: 'bold',
                transition: 'background-color 0.3s'
              }}
            >
              查看用户信息和Token验证 →
            </Link>
            <Link
              to="/resource-test"
              style={{
                display: 'inline-block',
                backgroundColor: '#9333ea',
                color: 'white',
                padding: '12px 30px',
                textDecoration: 'none',
                borderRadius: '5px',
                fontSize: '16px',
                fontWeight: 'bold',
                transition: 'background-color 0.3s'
              }}
            >
              🌐 测试异构资源服务器 →
            </Link>
          </div>
        </div>
      ) : (
        <div style={{
          background: 'white',
          padding: '30px',
          borderRadius: '10px',
          boxShadow: '0 2px 10px rgba(0,0,0,0.1)',
          marginBottom: '20px'
        }}>
          <p style={{
            color: '#666',
            lineHeight: '1.6',
            marginBottom: '30px',
            textAlign: 'center'
          }}>
            这是一个使用 React 构建的现代化登录演示应用。<br/>
            支持 Google、GitHub、Twitter OAuth2 登录和 Web3 钱包登录。
          </p>

          <div style={{ textAlign: 'center' }}>
            <Link
              to="/login"
              style={{
                display: 'inline-block',
                backgroundColor: '#28a745',
                color: 'white',
                padding: '12px 30px',
                textDecoration: 'none',
                borderRadius: '5px',
                fontSize: '16px',
                fontWeight: 'bold',
                transition: 'background-color 0.3s'
              }}
            >
              开始登录测试 →
            </Link>
          </div>
        </div>
      )}

      <div style={{
        display: 'grid',
        gridTemplateColumns: 'repeat(auto-fit, minmax(250px, 1fr))',
        gap: '20px'
      }}>
        <div style={{
          background: 'white',
          padding: '20px',
          borderRadius: '8px',
          boxShadow: '0 2px 10px rgba(0,0,0,0.1)',
          textAlign: 'center'
        }}>
          <div style={{ fontSize: '2em', marginBottom: '10px' }}>🔐</div>
          <h3 style={{ marginBottom: '10px', color: '#333' }}>安全认证</h3>
          <p style={{ color: '#666', fontSize: '14px' }}>
            支持Google、GitHub、Twitter OAuth2和Web3钱包登录，确保用户数据安全
          </p>
        </div>

        <div style={{
          background: 'white',
          padding: '20px',
          borderRadius: '8px',
          boxShadow: '0 2px 10px rgba(0,0,0,0.1)',
          textAlign: 'center'
        }}>
          <div style={{ fontSize: '2em', marginBottom: '10px' }}>🌐</div>
          <h3 style={{ marginBottom: '10px', color: '#333' }}>Web3 钱包</h3>
          <p style={{ color: '#666', fontSize: '14px' }}>
            支持 MetaMask 等以太坊钱包登录，基于签名验证的身份认证
          </p>
        </div>

        <div style={{
          background: 'white',
          padding: '20px',
          borderRadius: '8px',
          boxShadow: '0 2px 10px rgba(0,0,0,0.1)',
          textAlign: 'center'
        }}>
          <div style={{ fontSize: '2em', marginBottom: '10px' }}>🔄</div>
          <h3 style={{ marginBottom: '10px', color: '#333' }}>Token验证</h3>
          <p style={{ color: '#666', fontSize: '14px' }}>
            完整的Token验证功能，支持JWT和OAuth2 Token，自动刷新
          </p>
        </div>
      </div>
    </div>
  );
}

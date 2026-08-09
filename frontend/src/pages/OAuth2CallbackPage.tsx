import { useEffect, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { AuthService } from '../services/authService';

/**
 * OAuth2 回调页面
 * 处理SSO登录后的回调，提取并存储Token
 */
const OAuth2CallbackPage = () => {
  const navigate = useNavigate();
  const callbackHandled = useRef(false);

  useEffect(() => {
    if (callbackHandled.current) {
      return;
    }
    callbackHandled.current = true;

    const handleCallback = async () => {
      try {
        // 检查URL中是否有错误参数
        const urlParams = new URLSearchParams(window.location.search);
        const error = urlParams.get('error');
        const errorDescription = urlParams.get('error_description');

        if (error) {
          console.error('OAuth2登录失败:', error, errorDescription);
          // 重定向到登录页面并显示错误
          navigate(`/login?error=${encodeURIComponent(errorDescription || error)}`);
          return;
        }

        localStorage.removeItem('refreshToken');

        try {
          const refreshResponse = await AuthService.refreshToken();
          if (AuthService.diagnosticsEnabled()
              && !refreshResponse.accessToken) {
            throw new Error('刷新token失败：响应中没有accessToken');
          }
        } catch (error) {
          console.error('调用refreshToken API失败');
          throw new Error('获取accessToken失败', { cause: error });
        }
        
        const userData = await AuthService.getCurrentUser();
        localStorage.setItem('auth_user', JSON.stringify(userData));
        navigate('/');
      } catch (error) {
        console.error('处理OAuth2回调时出错:', error);
        navigate('/login?error=Callback%20processing%20failed');
      }
    };

    handleCallback();
  }, [navigate]);

  return (
    <div style={{
      display: 'flex',
      justifyContent: 'center',
      alignItems: 'center',
      minHeight: '100vh',
      flexDirection: 'column'
    }}>
      <div style={{ marginBottom: '20px' }}>正在处理登录...</div>
      <div style={{
        width: '40px',
        height: '40px',
        border: '4px solid #f3f3f3',
        borderTop: '4px solid #3498db',
        borderRadius: '50%',
        animation: 'spin 1s linear infinite'
      }}></div>
      <style>{`
        @keyframes spin {
          0% { transform: rotate(0deg); }
          100% { transform: rotate(360deg); }
        }
      `}</style>
    </div>
  );
};

export default OAuth2CallbackPage;

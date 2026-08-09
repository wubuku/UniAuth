import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../hooks/useAuth';
import { AuthService } from '../services/authService';
import Web3LoginButton from '../components/Web3LoginButton';
import { ForgotPasswordModal } from '../components/ForgotPasswordModal';
import { resolveLoginReturnPath } from '../utils/loginReturnPath';

interface VerificationModalProps {
  email: string;
  verificationCode: string;
  verificationLoading: boolean;
  verificationCountdown: number;
  verificationError: string | null;
  onCodeChange: (code: string) => void;
  onSubmit: () => void;
  onResend: () => void;
  onCancel: () => void;
}

function VerificationModal({
  email,
  verificationCode,
  verificationLoading,
  verificationCountdown,
  verificationError,
  onCodeChange,
  onSubmit,
  onResend,
  onCancel
}: VerificationModalProps) {
  return (
    <div style={{
      position: 'fixed',
      top: 0,
      left: 0,
      right: 0,
      bottom: 0,
      background: 'rgba(0,0,0,0.5)',
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'center',
      zIndex: 1000
    }}>
      <div style={{
        background: 'white',
        padding: '30px',
        borderRadius: '12px',
        boxShadow: '0 20px 60px rgba(0,0,0,0.3)',
        minWidth: '350px',
        textAlign: 'center'
      }}>
        <h3 style={{ marginTop: 0, color: '#333' }}>邮箱验证</h3>
        <p style={{ color: '#666', marginBottom: '20px' }}>
          请输入发送到 <strong>{email}</strong> 的6位验证码
        </p>

        <input
          type="text"
          value={verificationCode}
          onChange={(e) => onCodeChange(e.target.value.replace(/\D/g, '').slice(0, 6))}
          placeholder="请输入6位验证码"
          maxLength={6}
          style={{
            width: '100%',
            padding: '12px',
            fontSize: '24px',
            textAlign: 'center',
            letterSpacing: '8px',
            border: '2px solid #007bff',
            borderRadius: '8px',
            outline: 'none',
            marginBottom: '15px',
            boxSizing: 'border-box'
          }}
        />

        {verificationError && (
          <div style={{ color: 'red', fontSize: '14px', marginBottom: '15px' }}>
            ❌ {verificationError}
          </div>
        )}

        <button
          onClick={onSubmit}
          disabled={verificationLoading || verificationCode.length !== 6}
          style={{
            width: '100%',
            padding: '12px',
            background: verificationLoading || verificationCode.length !== 6 ? '#ccc' : '#28a745',
            color: 'white',
            border: 'none',
            borderRadius: '8px',
            fontSize: '16px',
            fontWeight: 'bold',
            cursor: verificationLoading || verificationCode.length !== 6 ? 'not-allowed' : 'pointer',
            marginBottom: '15px'
          }}
        >
          {verificationLoading ? '验证中...' : '确 定'}
        </button>

        <div style={{ marginBottom: '15px' }}>
          {verificationCountdown > 0 ? (
            <span style={{ color: '#666' }}>
              {verificationCountdown} 秒后可重新发送
            </span>
          ) : (
            <button
              onClick={onResend}
              style={{
                background: 'none',
                border: 'none',
                color: '#007bff',
                cursor: 'pointer',
                fontSize: '14px'
              }}
            >
              重新发送验证码
            </button>
          )}
        </div>

        <button
          onClick={onCancel}
          style={{
            background: 'none',
            border: 'none',
            color: '#666',
            cursor: 'pointer',
            fontSize: '14px'
          }}
        >
          取消
        </button>
      </div>
    </div>
  );
}

interface RegisterResponse {
  requireEmailVerification?: boolean;
  username?: string;
  email?: string;
  message?: string;
}

export default function LoginPage() {
  const { user, oauthLogin, localLogin, web3Login, loading, error } = useAuth();
  const navigate = useNavigate();
  const loginReturnPath = resolveLoginReturnPath(
    window.location.search,
    window.location.origin,
  );
  const [isRegisterMode, setIsRegisterMode] = useState(false);
  const [successMessage, setSuccessMessage] = useState<string | null>(null);
  const [web3Error, setWeb3Error] = useState<string | null>(null);

  const [formData, setFormData] = useState({
    username: '',
    email: '',
    password: '',
    displayName: ''
  });

  const [isEmailAsUsername, setIsEmailAsUsername] = useState(false);
  const [showVerificationModal, setShowVerificationModal] = useState(false);
  const [verificationEmail, setVerificationEmail] = useState('');
  const [verificationCode, setVerificationCode] = useState('');
  const [verificationChallengeHandle, setVerificationChallengeHandle] = useState('');
  const [verificationLoading, setVerificationLoading] = useState(false);
  const [verificationCountdown, setVerificationCountdown] = useState(0);
  const [verificationError, setVerificationError] = useState<string | null>(null);
  const [hasAutoSentCode, setHasAutoSentCode] = useState(false);
  const [registrationData, setRegistrationData] = useState<{
    username: string;
    email: string;
    password: string;
    displayName: string;
  } | null>(null);

  const [showForgotPasswordModal, setShowForgotPasswordModal] = useState(false);

  useEffect(() => {
    if (user) {
      navigate(loginReturnPath, { replace: true });
    }
  }, [user, navigate, loginReturnPath]);

  const isValidEmail = (value: string): boolean => {
    return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value);
  };

  const handleInputChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const { name, value } = e.target;

    if (name === 'username') {
      if (isValidEmail(value)) {
        setFormData(prev => ({ ...prev, username: value, email: value }));
        setIsEmailAsUsername(true);
      } else if (isEmailAsUsername) {
        setFormData(prev => ({ ...prev, username: value, email: '' }));
        setIsEmailAsUsername(false);
      } else {
        setFormData(prev => ({ ...prev, username: value }));
      }
    } else {
      setFormData(prev => ({ ...prev, [name]: value }));
    }
  };

  const startCountdown = (seconds: number) => {
    setVerificationCountdown(seconds);
    const timer = setInterval(() => {
      setVerificationCountdown(prev => {
        if (prev <= 1) {
          clearInterval(timer);
          return 0;
        }
        return prev - 1;
      });
    }, 1000);
  };

  const handleSendVerificationCode = async () => {
    if (!verificationEmail) return;

    setVerificationLoading(true);
    setVerificationError(null);

    try {
      const response = await AuthService.sendVerificationCode({
        email: verificationEmail,
        purpose: 'REGISTRATION'
      });

      setVerificationChallengeHandle(response.challengeHandle);
      startCountdown(response.resendAfter);
      setSuccessMessage(`验证码请求已受理，请检查 ${verificationEmail}`);
    } catch (err) {
      const message = err instanceof Error ? err.message : '发送验证码失败';
      setVerificationError(message);
    } finally {
      setVerificationLoading(false);
    }
  };

  useEffect(() => {
    if (showVerificationModal && verificationEmail && !verificationLoading && !hasAutoSentCode && verificationCountdown === 0) {
      setHasAutoSentCode(true);
      handleSendVerificationCode();
    }
  }, [showVerificationModal, verificationEmail, verificationLoading, hasAutoSentCode, verificationCountdown]);

  const handleVerifyEmail = async () => {
    if (!verificationCode || verificationCode.length !== 6) {
      setVerificationError('请输入6位验证码');
      return;
    }
    if (!verificationChallengeHandle || !registrationData) {
      setVerificationError('验证请求已失效，请重新发送验证码');
      return;
    }

    setVerificationLoading(true);
    setVerificationError(null);

    try {
      const response = await AuthService.verifyEmail({
        challengeHandle: verificationChallengeHandle,
        username: registrationData.username,
        email: verificationEmail,
        password: registrationData.password,
        displayName: registrationData.displayName,
        verificationCode
      });

      if (response.success && response.accessToken) {
        setShowVerificationModal(false);
        setVerificationCode('');
        setVerificationChallengeHandle('');
        setRegistrationData(null);
        setSuccessMessage('邮箱验证成功！');

        localStorage.setItem('accessToken', response.accessToken);

        const userData = {
          id: response.user.id,
          username: response.user.username,
          email: response.user.email,
          displayName: response.user.displayName,
          provider: 'local' as const
        };
        localStorage.setItem('auth_user', JSON.stringify(userData));
        window.location.href = loginReturnPath;
      }
    } catch (err) {
      const message = err instanceof Error ? err.message : '验证码验证失败';
      setVerificationError(message);
    } finally {
      setVerificationLoading(false);
    }
  };

  const handleLocalAuth = async (e: React.FormEvent) => {
    e.preventDefault();
    setSuccessMessage(null);
    setVerificationError(null);

    try {
      if (isRegisterMode) {
        setVerificationLoading(true);
        const response = await AuthService.register(formData) as RegisterResponse;

        if (response.requireEmailVerification) {
          setVerificationEmail(response.email || formData.email);
          setRegistrationData({
            username: formData.username,
            email: formData.email,
            password: formData.password,
            displayName: formData.displayName
          });
          setVerificationChallengeHandle('');
          setVerificationCode('');
          setVerificationCountdown(0);
          setHasAutoSentCode(false);
          setShowVerificationModal(true);
          setVerificationLoading(false);
          return;
        }

        setSuccessMessage('注册成功！请登录。');
        setIsRegisterMode(false);
      } else {
        await localLogin(formData.username, formData.password);
        setSuccessMessage('登录成功！正在跳转...');
      }
    } catch (err: any) {
      const errorData = err.response?.data;
      const errorCode = errorData?.errorCode || errorData?.error;
      let message = errorData?.message || errorData?.error || '操作失败';

      if (errorCode === 'EMAIL_ALREADY_REGISTERED') {
        message = '该邮箱已注册，请使用忘记密码功能重置密码';
      } else if (errorCode === 'EMAIL_NOT_REGISTERED') {
        message = '该邮箱未注册，请先完成注册';
      }

      setWeb3Error(message);
    } finally {
      setVerificationLoading(false);
    }
  };

  const handleOAuthLogin = (provider: 'google' | 'github' | 'x') => {
    oauthLogin(provider);
  };

  return (
    <div style={{
      minHeight: '100vh',
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'center',
      background: 'linear-gradient(135deg, #667eea 0%, #764ba2 100%)',
      padding: '20px'
    }}>
      <div style={{
        maxWidth: '450px',
        width: '100%',
        padding: '40px',
        background: 'white',
        borderRadius: '10px',
        boxShadow: '0 15px 35px rgba(0,0,0,0.1)',
        textAlign: 'center'
      }}>
        <div style={{
          background: '#ff6b6b',
          color: 'white',
          padding: '12px',
          borderRadius: '8px',
          marginBottom: '20px',
          fontSize: '16px',
          fontWeight: 'bold'
        }}>
          🚀 当前使用：React 前端实现 (Modern SPA)
        </div>

        <div style={{
          display: 'flex',
          marginBottom: '30px',
          border: '1px solid #e0e0e0',
          borderRadius: '8px',
          overflow: 'hidden'
        }}>
          <button
            onClick={() => setIsRegisterMode(false)}
            style={{
              flex: 1,
              padding: '12px',
              border: 'none',
              background: isRegisterMode ? '#f8f9fa' : '#007bff',
              color: isRegisterMode ? '#666' : 'white',
              fontSize: '16px',
              fontWeight: 'bold',
              cursor: 'pointer',
              transition: 'all 0.3s ease'
            }}
          >
            登录
          </button>
          <button
            onClick={() => setIsRegisterMode(true)}
            style={{
              flex: 1,
              padding: '12px',
              border: 'none',
              background: isRegisterMode ? '#007bff' : '#f8f9fa',
              color: isRegisterMode ? 'white' : '#666',
              fontSize: '16px',
              fontWeight: 'bold',
              cursor: 'pointer',
              transition: 'all 0.3s ease'
            }}
          >
            注册
          </button>
        </div>

        {successMessage && (
          <div style={{
            background: '#d4edda',
            color: '#155724',
            padding: '12px',
            borderRadius: '8px',
            marginBottom: '20px',
            fontSize: '14px',
            fontWeight: 'bold'
          }}>
            ✅ {successMessage}
          </div>
        )}

        {(error || web3Error) && (
          <div style={{
            background: '#f8d7da',
            color: '#721c24',
            padding: '12px',
            borderRadius: '8px',
            marginBottom: '20px',
            fontSize: '14px'
          }}>
            {error || web3Error}
          </div>
        )}

        <form onSubmit={handleLocalAuth} style={{ marginBottom: '30px' }}>
          <div style={{ display: 'flex', flexDirection: 'column', gap: '15px' }}>
            <input
              type="text"
              name="username"
              placeholder="用户名"
              value={formData.username}
              onChange={handleInputChange}
              required
              style={{
                padding: '12px',
                border: '1px solid #ddd',
                borderRadius: '8px',
                fontSize: '16px',
                outline: 'none'
              }}
            />

            {isRegisterMode && (
              <>
                <input
                  type="email"
                  name="email"
                  placeholder="邮箱"
                  value={formData.email}
                  onChange={handleInputChange}
                  disabled={isEmailAsUsername}
                  required={!isEmailAsUsername}
                  style={{
                    padding: '12px',
                    border: '1px solid #ddd',
                    borderRadius: '8px',
                    fontSize: '16px',
                    outline: 'none',
                    backgroundColor: isEmailAsUsername ? '#f5f5f5' : 'white',
                    cursor: isEmailAsUsername ? 'not-allowed' : 'text'
                  }}
                  {...(isEmailAsUsername ? { readOnly: true } : {})}
                />
                {isEmailAsUsername && (
                  <div style={{
                    fontSize: '12px',
                    color: '#666',
                    marginTop: '-10px',
                    marginBottom: '10px'
                  }}>
                    ℹ️ 当用户名是邮箱地址时，邮箱字段将使用相同的地址
                  </div>
                )}

                <input
                  type="text"
                  name="displayName"
                  placeholder="显示名称"
                  value={formData.displayName}
                  onChange={handleInputChange}
                  required
                  style={{
                    padding: '12px',
                    border: '1px solid #ddd',
                    borderRadius: '8px',
                    fontSize: '16px',
                    outline: 'none'
                  }}
                />
              </>
            )}

            <input
              type="password"
              name="password"
              placeholder="密码"
              value={formData.password}
              onChange={handleInputChange}
              required
              style={{
                padding: '12px',
                border: '1px solid #ddd',
                borderRadius: '8px',
                fontSize: '16px',
                outline: 'none'
              }}
            />

            <button
              type="submit"
              disabled={loading || verificationLoading}
              style={{
                backgroundColor: (loading || verificationLoading) ? '#ccc' : '#28a745',
                color: 'white',
                border: 'none',
                padding: '15px 20px',
                borderRadius: '8px',
                fontSize: '16px',
                fontWeight: 'bold',
                cursor: (loading || verificationLoading) ? 'not-allowed' : 'pointer',
                transition: 'all 0.3s ease'
              }}
            >
              {(loading || verificationLoading) ? '处理中...' : (isRegisterMode ? '注册' : '登录')}
            </button>

            {!isRegisterMode && (
              <button
                type="button"
                onClick={() => setShowForgotPasswordModal(true)}
                style={{
                  background: 'none',
                  border: 'none',
                  color: '#007bff',
                  cursor: 'pointer',
                  fontSize: '14px',
                  marginTop: '10px'
                }}
              >
                忘记密码？
              </button>
            )}
          </div>
        </form>

        <div style={{
          margin: '20px 0',
          position: 'relative',
          textAlign: 'center'
        }}>
          <div style={{
            borderTop: '1px solid #eee',
            position: 'absolute',
            top: '50%',
            left: 0,
            right: 0
          }}></div>
          <span style={{
            background: 'white',
            padding: '0 10px',
            color: '#666',
            fontSize: '14px'
          }}>
            或使用第三方登录
          </span>
        </div>

        <div style={{ display: 'flex', flexDirection: 'column', gap: '10px' }}>
          <button
            onClick={() => handleOAuthLogin('google')}
            style={{
              backgroundColor: '#db4437',
              color: 'white',
              border: 'none',
              padding: '12px 15px',
              borderRadius: '8px',
              fontSize: '14px',
              fontWeight: 'bold',
              cursor: 'pointer',
              transition: 'all 0.3s ease',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              gap: '8px'
            }}
            onMouseOver={(e) => {
              e.currentTarget.style.backgroundColor = '#c23321';
              e.currentTarget.style.transform = 'translateY(-1px)';
            }}
            onMouseOut={(e) => {
              e.currentTarget.style.backgroundColor = '#db4437';
              e.currentTarget.style.transform = 'translateY(0)';
            }}
          >
            <span style={{ fontSize: '16px' }}>🌐</span>
            Google 登录
          </button>

          <button
            onClick={() => handleOAuthLogin('github')}
            style={{
              backgroundColor: '#24292e',
              color: 'white',
              border: 'none',
              padding: '12px 15px',
              borderRadius: '8px',
              fontSize: '14px',
              fontWeight: 'bold',
              cursor: 'pointer',
              transition: 'all 0.3s ease',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              gap: '8px'
            }}
            onMouseOver={(e) => {
              e.currentTarget.style.backgroundColor = '#1a1a1a';
              e.currentTarget.style.transform = 'translateY(-1px)';
            }}
            onMouseOut={(e) => {
              e.currentTarget.style.backgroundColor = '#24292e';
              e.currentTarget.style.transform = 'translateY(0)';
            }}
          >
            <span style={{ fontSize: '16px' }}>🐙</span>
            GitHub 登录
          </button>

          <button
            onClick={() => handleOAuthLogin('x')}
            style={{
              backgroundColor: '#1da1f2',
              color: 'white',
              border: 'none',
              padding: '12px 15px',
              borderRadius: '8px',
              fontSize: '14px',
              fontWeight: 'bold',
              cursor: 'pointer',
              transition: 'all 0.3s ease',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              gap: '8px'
            }}
            onMouseOver={(e) => {
              e.currentTarget.style.backgroundColor = '#0d8ecf';
              e.currentTarget.style.transform = 'translateY(-1px)';
            }}
            onMouseOut={(e) => {
              e.currentTarget.style.backgroundColor = '#1da1f2';
              e.currentTarget.style.transform = 'translateY(0)';
            }}
          >
            <span style={{ fontSize: '16px' }}>🐦</span>
            Twitter 登录
          </button>

          <Web3LoginButton onError={setWeb3Error} onLogin={web3Login} />
        </div>

        <a
          href="/"
          style={{
            display: 'inline-block',
            marginTop: '20px',
            color: '#007bff',
            textDecoration: 'none',
            fontSize: '14px'
          }}
        >
          ← 返回首页
        </a>
      </div>

      {showVerificationModal && (
        <VerificationModal
          email={verificationEmail}
          verificationCode={verificationCode}
          verificationLoading={verificationLoading}
          verificationCountdown={verificationCountdown}
          verificationError={verificationError}
          onCodeChange={setVerificationCode}
          onSubmit={handleVerifyEmail}
          onResend={handleSendVerificationCode}
          onCancel={() => {
            setShowVerificationModal(false);
            setVerificationCode('');
            setVerificationChallengeHandle('');
            setVerificationError(null);
            setHasAutoSentCode(false);
            setRegistrationData(null);
            setFormData(prev => ({ ...prev, password: '' }));
          }}
        />
      )}

      <ForgotPasswordModal
        isOpen={showForgotPasswordModal}
        onClose={() => setShowForgotPasswordModal(false)}
      />
    </div>
  );
}

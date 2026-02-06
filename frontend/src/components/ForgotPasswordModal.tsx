import React, { useState } from 'react';
import { AuthService } from '../services/authService';

interface ForgotPasswordModalProps {
  isOpen: boolean;
  onClose: () => void;
  onSwitchToRegister?: () => void;
}

type Step = 'email' | 'verify' | 'success';

export const ForgotPasswordModal: React.FC<ForgotPasswordModalProps> = ({ isOpen, onClose, onSwitchToRegister }) => {
  const [step, setStep] = useState<Step>('email');
  const [email, setEmail] = useState('');
  const [verificationCode, setVerificationCode] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [loading, setLoading] = useState(false);
  const [countdown, setCountdown] = useState(0);
  const [error, setError] = useState<React.ReactNode>(null);

  const handleRequestCode = async () => {
    if (!email) {
      setError('请输入邮箱地址');
      return;
    }

    setLoading(true);
    setError(null);

    try {
      const response = await AuthService.requestPasswordReset(email);
      if (response.success) {
        setStep('verify');
        setCountdown(60);
        const timer = setInterval(() => {
          setCountdown((prev) => {
            if (prev <= 1) {
              clearInterval(timer);
              return 0;
            }
            return prev - 1;
          });
        }, 1000);
      } else if (response.errorCode === 'EMAIL_NOT_REGISTERED') {
        setError(
          <span>
            {response.message}
            {onSwitchToRegister && (
              <button
                type="button"
                onClick={onSwitchToRegister}
                style={{
                  background: 'none',
                  border: 'none',
                  color: '#007bff',
                  cursor: 'pointer',
                  marginLeft: '8px',
                  fontSize: '14px',
                  textDecoration: 'underline',
                  padding: 0
                }}
              >
            去注册
              </button>
            )}
          </span>
        );
      } else {
        setError(response.message || '发送失败，请稍后重试');
      }
    } catch (err: any) {
      const errorData = err.response?.data;
      const errorCode = errorData?.errorCode;
      const message = errorData?.message || '发送失败，请稍后重试';

      if (errorCode === 'EMAIL_NOT_REGISTERED') {
        setError(
          <span>
            {message}
            {onSwitchToRegister && (
              <button
                type="button"
                onClick={onSwitchToRegister}
                style={{
                  background: 'none',
                  border: 'none',
                  color: '#007bff',
                  cursor: 'pointer',
                  marginLeft: '8px',
                  fontSize: '14px',
                  textDecoration: 'underline',
                  padding: 0
                }}
              >
            去注册
              </button>
            )}
          </span>
        );
      } else {
        setError(message);
      }
    } finally {
      setLoading(false);
    }
  };

  const handleResetPassword = async () => {
    setError(null);

    if (newPassword !== confirmPassword) {
      setError('两次输入的密码不一致');
      return;
    }

    if (newPassword.length < 6) {
      setError('密码长度至少6位');
      return;
    }

    setLoading(true);

    try {
      const response = await AuthService.resetPassword({
        email,
        verificationCode,
        newPassword
      });

      if (response.success) {
        setStep('success');
      } else {
        setError(response.message || '重置失败，请检查验证码');
      }
    } catch (err) {
      const message = err instanceof Error ? err.message : '重置失败，请检查验证码';
      setError(message);
    } finally {
      setLoading(false);
    }
  };

  const handleClose = () => {
    setStep('email');
    setEmail('');
    setVerificationCode('');
    setNewPassword('');
    setConfirmPassword('');
    setError(null);
    onClose();
  };

  if (!isOpen) return null;

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
        {step === 'email' && (
          <>
            <h3 style={{ marginTop: 0, color: '#333' }}>找回密码</h3>
            <p style={{ color: '#666', marginBottom: '20px' }}>
              请输入您的邮箱地址，我们将发送验证码到您的邮箱
            </p>
            <input
              type="email"
              value={email}
              onChange={(e) => {
                setEmail(e.target.value);
                setError(null);
              }}
              placeholder="请输入邮箱地址"
              style={{
                width: '100%',
                padding: '12px',
                fontSize: '16px',
                border: '1px solid #ddd',
                borderRadius: '8px',
                outline: 'none',
                marginBottom: '15px',
                boxSizing: 'border-box'
              }}
            />
            {error && (
              <div style={{ color: 'red', fontSize: '14px', marginBottom: '15px' }}>
                ❌ {error}
              </div>
            )}
            <button
              onClick={handleRequestCode}
              disabled={loading || !email}
              style={{
                width: '100%',
                padding: '12px',
                background: (loading || !email) ? '#ccc' : '#007bff',
                color: 'white',
                border: 'none',
                borderRadius: '8px',
                fontSize: '16px',
                fontWeight: 'bold',
                cursor: (loading || !email) ? 'not-allowed' : 'pointer',
                marginBottom: '15px'
              }}
            >
              {loading ? '发送中...' : '发送验证码'}
            </button>
          </>
        )}

        {step === 'verify' && (
          <>
            <h3 style={{ marginTop: 0, color: '#333' }}>输入验证码</h3>
            <p style={{ color: '#666', marginBottom: '20px' }}>
              请输入发送到 <strong>{email}</strong> 的验证码
            </p>
            <input
              type="text"
              value={verificationCode}
              onChange={(e) => {
                setVerificationCode(e.target.value.replace(/\D/g, '').slice(0, 6));
                setError(null);
              }}
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
            <input
              type="password"
              value={newPassword}
              onChange={(e) => {
                setNewPassword(e.target.value);
                setError(null);
              }}
              placeholder="请输入新密码"
              style={{
                width: '100%',
                padding: '12px',
                fontSize: '16px',
                border: '1px solid #ddd',
                borderRadius: '8px',
                outline: 'none',
                marginBottom: '10px',
                boxSizing: 'border-box'
              }}
            />
            <input
              type="password"
              value={confirmPassword}
              onChange={(e) => {
                setConfirmPassword(e.target.value);
                setError(null);
              }}
              placeholder="请确认新密码"
              style={{
                width: '100%',
                padding: '12px',
                fontSize: '16px',
                border: '1px solid #ddd',
                borderRadius: '8px',
                outline: 'none',
                marginBottom: '15px',
                boxSizing: 'border-box'
              }}
            />
            {error && (
              <div style={{ color: 'red', fontSize: '14px', marginBottom: '15px' }}>
                ❌ {error}
              </div>
            )}
            <button
              onClick={handleResetPassword}
              disabled={loading || verificationCode.length !== 6 || !newPassword || !confirmPassword}
              style={{
                width: '100%',
                padding: '12px',
                background: (loading || verificationCode.length !== 6 || !newPassword || !confirmPassword) ? '#ccc' : '#28a745',
                color: 'white',
                border: 'none',
                borderRadius: '8px',
                fontSize: '16px',
                fontWeight: 'bold',
                cursor: (loading || verificationCode.length !== 6 || !newPassword || !confirmPassword) ? 'not-allowed' : 'pointer',
                marginBottom: '15px'
              }}
            >
              {loading ? '重置中...' : '确认重置'}
            </button>
            <div style={{ marginBottom: '15px' }}>
              {countdown > 0 ? (
                <span style={{ color: '#666' }}>
                  {countdown} 秒后可重新发送
                </span>
              ) : (
                <button
                  onClick={handleRequestCode}
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
          </>
        )}

        {step === 'success' && (
          <>
            <h3 style={{ marginTop: 0, color: '#28a745' }}>🎉 密码重置成功</h3>
            <p style={{ color: '#666', marginBottom: '20px' }}>
              您的密码已成功重置，请使用新密码登录
            </p>
            <button
              onClick={handleClose}
              style={{
                width: '100%',
                padding: '12px',
                background: '#007bff',
                color: 'white',
                border: 'none',
                borderRadius: '8px',
                fontSize: '16px',
                fontWeight: 'bold',
                cursor: 'pointer',
                marginBottom: '15px'
              }}
            >
              返回登录
            </button>
          </>
        )}

        <button
          onClick={handleClose}
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
};

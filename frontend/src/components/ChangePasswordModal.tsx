import { useState } from 'react';
import { AuthService } from '../services/authService';

interface ChangePasswordModalProps {
  isOpen: boolean;
  onClose: () => void;
  onChanged: () => void;
}

export function ChangePasswordModal({
  isOpen,
  onClose,
  onChanged,
}: ChangePasswordModalProps) {
  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [newPasswordConfirm, setNewPasswordConfirm] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  if (!isOpen) {
    return null;
  }

  const close = () => {
    if (submitting) {
      return;
    }
    setCurrentPassword('');
    setNewPassword('');
    setNewPasswordConfirm('');
    setError(null);
    onClose();
  };

  const submit = async () => {
    setError(null);
    if (newPassword !== newPasswordConfirm) {
      setError('两次输入的新密码不一致');
      return;
    }
    if (newPassword.length < 8) {
      setError('新密码至少需要8位');
      return;
    }

    setSubmitting(true);
    try {
      await AuthService.changePassword({
        currentPassword,
        newPassword,
        newPasswordConfirm,
      });
      onChanged();
    } catch (requestError) {
      setError(
        requestError instanceof Error
          ? requestError.message
          : '修改密码失败'
      );
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-labelledby="change-password-title"
      style={{
        position: 'fixed',
        inset: 0,
        background: 'rgba(0, 0, 0, 0.45)',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        zIndex: 1000,
        padding: '20px',
      }}
    >
      <div style={{
        background: 'white',
        width: 'min(420px, 100%)',
        padding: '28px',
        borderRadius: '8px',
        boxShadow: '0 20px 60px rgba(0, 0, 0, 0.25)',
      }}>
        <h2 id="change-password-title" style={{ marginTop: 0 }}>
          修改密码
        </h2>
        <p style={{ color: '#666' }}>
          修改成功后需要使用新密码重新登录。
        </p>
        {error && (
          <div role="alert" style={{
            color: '#721c24',
            background: '#f8d7da',
            padding: '10px',
            borderRadius: '4px',
            marginBottom: '12px',
          }}>
            {error}
          </div>
        )}
        <input
          type="password"
          aria-label="当前密码"
          placeholder="当前密码"
          value={currentPassword}
          onChange={(event) => setCurrentPassword(event.target.value)}
          style={inputStyle}
        />
        <input
          type="password"
          aria-label="新密码"
          placeholder="新密码（至少8位）"
          value={newPassword}
          onChange={(event) => setNewPassword(event.target.value)}
          style={inputStyle}
        />
        <input
          type="password"
          aria-label="确认新密码"
          placeholder="确认新密码"
          value={newPasswordConfirm}
          onChange={(event) => setNewPasswordConfirm(event.target.value)}
          style={inputStyle}
        />
        <div style={{ display: 'flex', gap: '10px', justifyContent: 'flex-end' }}>
          <button type="button" onClick={close} disabled={submitting}>
            取消
          </button>
          <button
            type="button"
            onClick={submit}
            disabled={submitting || !currentPassword || !newPassword || !newPasswordConfirm}
          >
            {submitting ? '提交中...' : '确认修改'}
          </button>
        </div>
      </div>
    </div>
  );
}

const inputStyle = {
  display: 'block',
  width: '100%',
  boxSizing: 'border-box' as const,
  padding: '11px',
  marginBottom: '10px',
  border: '1px solid #d0d7de',
  borderRadius: '4px',
  fontSize: '15px',
};

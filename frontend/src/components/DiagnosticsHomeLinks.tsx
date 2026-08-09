import { Link } from 'react-router-dom';

export default function DiagnosticsHomeLinks() {
  return (
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
  );
}

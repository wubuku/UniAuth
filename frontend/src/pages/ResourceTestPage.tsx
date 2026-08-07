import React, { useState, useEffect } from 'react';

/**
 * 异构资源服务器集成测试页面
 * 测试从 Python 资源服务器获取受保护资源
 */
const ResourceTestPage: React.FC = () => {
  const [resourceData, setResourceData] = useState<any>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [testStatus, setTestStatus] = useState<'idle' | 'testing' | 'success' | 'error'>('idle');
  const [accessToken, setAccessToken] = useState<string | null>(
    () => localStorage.getItem('accessToken')
  );
  const [activeTest, setActiveTest] = useState<string | null>(null);
  const [copySuccess, setCopySuccess] = useState(false);

  // 拷贝JSON数据到剪贴板
  const copyToClipboard = (text: string) => {
    navigator.clipboard.writeText(text)
      .then(() => {
        setCopySuccess(true);
        setTimeout(() => setCopySuccess(false), 2000);
      })
      .catch(err => {
        console.error('无法拷贝文本:', err);
      });
  };

  // 监听 access token 变化
  useEffect(() => {
    console.log('=== 开始检查token状态 ===');
    // 从localStorage获取token（仅在需要访问不同域资源服务器时使用）
    const token = localStorage.getItem('accessToken');
    console.log('从localStorage获取的token:', token ? 'Present' : 'Missing');
    console.log('最终设置的token:', token ? 'Present' : 'Missing');
    
    // 定期检查 token 变化
    const interval = setInterval(() => {
      console.log('=== 定期检查token变化 ===');
      const updatedToken = localStorage.getItem('accessToken');
      console.log('从localStorage获取的updatedToken:', updatedToken ? 'Present' : 'Missing');
      if (updatedToken !== accessToken) {
        console.log('token发生变化，更新状态');
        setAccessToken(updatedToken);
      }
    }, 1000); // 每1秒检查一次
    
    return () => clearInterval(interval);
  }, [accessToken]);

  const fetchProtectedResource = async () => {
    setActiveTest('protected-resource');
    setLoading(true);
    setError(null);
    setResourceData(null);
    setTestStatus('testing');

    try {
      // 从 localStorage 获取 access token（仅在需要访问不同域资源服务器时使用）
      const token = localStorage.getItem('accessToken');

      if (!token) {
        setError('❌ 未找到访问令牌，请先登录');
        setTestStatus('error');
        setLoading(false);
        return;
      }

      console.log('📤 从 Python 服务器获取受保护资源...');
      console.log('使用的令牌:', token);
      console.log('令牌长度:', token.length);
      
      // 解析令牌头，检查 kid
      try {
        const tokenParts = token.split('.');
        const headerPart = tokenParts[0];
        const header = JSON.parse(atob(headerPart));
        console.log('令牌头:', header);
        console.log('令牌 kid:', header.kid);
      } catch (e) {
        console.error('解析令牌头失败:', e);
      }

      // 调用 Python 资源服务器
      const response = await fetch('http://localhost:5002/api/protected', {
        method: 'GET',
        headers: {
          'Authorization': `Bearer ${token}`,
          'Content-Type': 'application/json',
        },
        credentials: 'include',
      });

      if (!response.ok) {
        const errorData = await response.json();
        throw new Error(errorData.error || `HTTP ${response.status}: ${response.statusText}`);
      }

      const data = await response.json();
      console.log('✅ 成功获取受保护资源:', data);
      setResourceData(data);
      setTestStatus('success');
    } catch (err: any) {
      const errorMessage = err instanceof Error ? err.message : String(err);
      console.error('❌ 获取资源失败:', errorMessage);
      setError(errorMessage);
      setTestStatus('error');
    } finally {
      setLoading(false);
      setActiveTest(null);
    }
  };

  const testHealthCheck = async () => {
    setActiveTest('health-check');
    setLoading(true);
    setError(null);
    setResourceData(null);
    setTestStatus('testing');

    try {
      console.log('🏥 测试资源服务器健康状态...');
      const response = await fetch('http://localhost:5002/health');

      if (!response.ok) {
        throw new Error(`HTTP ${response.status}: ${response.statusText}`);
      }

      const data = await response.json();
      console.log('✅ 健康检查通过:', data);
      setResourceData(data);
      setTestStatus('success');
    } catch (err: any) {
      const errorMessage = err instanceof Error ? err.message : String(err);
      console.error('❌ 健康检查失败:', errorMessage);
      setError(errorMessage);
      setTestStatus('error');
    } finally {
      setLoading(false);
      setActiveTest(null);
    }
  };

  const testJwks = async () => {
    setActiveTest('jwks');
    setLoading(true);
    setError(null);
    setResourceData(null);
    setTestStatus('testing');

    try {
      console.log('🔑 从认证服务器获取 JWKS...');
      const response = await fetch('/oauth2/jwks');

      if (!response.ok) {
        throw new Error(`HTTP ${response.status}: ${response.statusText}`);
      }

      const data = await response.json();
      console.log('✅ 成功获取 JWKS:', data);
      setResourceData(data);
      setTestStatus('success');
    } catch (err: any) {
      const errorMessage = err instanceof Error ? err.message : String(err);
      console.error('❌ 获取 JWKS 失败:', errorMessage);
      setError(errorMessage);
      setTestStatus('error');
    } finally {
      setLoading(false);
      setActiveTest(null);
    }
  };

  const testIntrospect = async () => {
    setActiveTest('introspect');
    setLoading(true);
    setError(null);
    setResourceData(null);
    setTestStatus('testing');

    try {
      const token = localStorage.getItem('accessToken');

      if (!token) {
        setError('❌ 未找到访问令牌，请先登录');
        setTestStatus('error');
        setLoading(false);
        return;
      }

      console.log('🔍 测试令牌内省...');
      const response = await fetch('/oauth2/api/introspect', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/x-www-form-urlencoded',
        },
        body: `token=${encodeURIComponent(token)}`,
      });

      if (!response.ok) {
        throw new Error(`HTTP ${response.status}: ${response.statusText}`);
      }

      const data = await response.json();
      console.log('✅ 内省响应:', data);
      setResourceData(data);
      setTestStatus('success');
    } catch (err: any) {
      const errorMessage = err instanceof Error ? err.message : String(err);
      console.error('❌ 内省失败:', errorMessage);
      setError(errorMessage);
      setTestStatus('error');
    } finally {
      setLoading(false);
      setActiveTest(null);
    }
  };

  return (
    <div className="min-h-screen bg-gradient-to-br from-gray-50 to-gray-100 p-4 md:p-8">
      <div className="max-w-6xl mx-auto">
        {/* 页面头部 */}
        <header className="mb-10">
          <h1 className="text-4xl md:text-5xl font-bold text-gray-800 mb-3">🌐 异构资源服务器集成测试</h1>
          <p className="text-xl text-gray-600 max-w-3xl">
            验证 Python 资源服务器与 Spring Boot OAuth2 认证服务器的安全集成
          </p>
          
          {/* 登录状态指示器 */}
          <div className={`mt-4 inline-block px-4 py-2 rounded-full text-sm font-medium ${accessToken ? 'bg-green-100 text-green-800' : 'bg-red-100 text-red-800'}`}>
            {accessToken ? '✅ 已登录' : '❌ 未登录'}
          </div>
        </header>

        {/* 说明区域 */}
        <div className="bg-white rounded-xl shadow-md p-6 mb-8 border-l-4 border-blue-500">
          <h2 className="text-xl font-semibold text-blue-900 mb-4 flex items-center gap-2">
            📋 测试说明
          </h2>
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            <div>
              <h3 className="font-medium text-gray-800 mb-2">🔧 前置条件</h3>
              <ul className="space-y-2 text-gray-700">
                <li className="flex items-start gap-2">
                  <span className="text-green-500 mt-1">✅</span>
                  <span>Java 认证服务器运行在 8081 端口</span>
                </li>
                <li className="flex items-start gap-2">
                  <span className="text-green-500 mt-1">✅</span>
                  <span>Python 资源服务器运行在 5002 端口</span>
                </li>
                <li className="flex items-start gap-2">
                  <span className="text-green-500 mt-1">✅</span>
                  <span>已登录获取访问令牌</span>
                </li>
              </ul>
            </div>
            <div>
              <h3 className="font-medium text-gray-800 mb-2">🔍 测试步骤</h3>
              <ul className="space-y-2 text-gray-700">
                <li className="flex items-start gap-2">
                  <span className="text-blue-500 mt-1">1.</span>
                  <span>点击下方按钮进行集成测试</span>
                </li>
                <li className="flex items-start gap-2">
                  <span className="text-blue-500 mt-1">2.</span>
                  <span>查看测试结果和详细信息</span>
                </li>
                <li className="flex items-start gap-2">
                  <span className="text-blue-500 mt-1">3.</span>
                  <span>检查浏览器控制台了解过程</span>
                </li>
              </ul>
            </div>
          </div>
        </div>

        {/* 测试按钮区域 */}
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4 mb-8">
          {/* 健康检查 */}
          <button
            onClick={testHealthCheck}
            disabled={loading}
            className={`relative overflow-hidden rounded-xl font-semibold py-4 px-6 transition-all duration-300 transform hover:scale-105 ${loading ? 'bg-gray-400 cursor-not-allowed' : 'bg-green-500 hover:bg-green-600 text-white'}`}
          >
            <div className="flex items-center gap-2">
              {activeTest === 'health-check' && loading && (
                <svg className="animate-spin -ml-1 mr-2 h-5 w-5 text-white" xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24">
                  <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4"></circle>
                  <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"></path>
                </svg>
              )}
              {!loading && '🏥 资源服务器健康检查'}
              {loading && !activeTest && '⏳ 检测中...'}
            </div>
          </button>

          {/* JWKS 测试 */}
          <button
            onClick={testJwks}
            disabled={loading}
            className={`relative overflow-hidden rounded-xl font-semibold py-4 px-6 transition-all duration-300 transform hover:scale-105 ${loading ? 'bg-gray-400 cursor-not-allowed' : 'bg-blue-500 hover:bg-blue-600 text-white'}`}
          >
            <div className="flex items-center gap-2">
              {activeTest === 'jwks' && loading && (
                <svg className="animate-spin -ml-1 mr-2 h-5 w-5 text-white" xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24">
                  <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4"></circle>
                  <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"></path>
                </svg>
              )}
              {!loading && '🔑 测试 JWKS 端点'}
              {loading && !activeTest && '⏳ 获取中...'}
            </div>
          </button>

          {/* Token 验证 */}
          <button
            onClick={testIntrospect}
            disabled={loading}
            className={`relative overflow-hidden rounded-xl font-semibold py-4 px-6 transition-all duration-300 transform hover:scale-105 ${loading ? 'bg-gray-400 cursor-not-allowed' : 'bg-purple-500 hover:bg-purple-600 text-white'}`}
          >
            <div className="flex items-center gap-2">
              {activeTest === 'introspect' && loading && (
                <svg className="animate-spin -ml-1 mr-2 h-5 w-5 text-white" xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24">
                  <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4"></circle>
                  <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"></path>
                </svg>
              )}
              {!loading && '🔍 测试 Token 内省'}
              {loading && !activeTest && '⏳ 验证中...'}
            </div>
          </button>

          {/* 获取资源 */}
          <button
            onClick={fetchProtectedResource}
            disabled={loading}
            className={`relative overflow-hidden rounded-xl font-semibold py-4 px-6 transition-all duration-300 transform hover:scale-105 ${loading ? 'bg-gray-400 cursor-not-allowed' : 'bg-red-500 hover:bg-red-600 text-white'}`}
          >
            <div className="flex items-center gap-2">
              {activeTest === 'protected-resource' && loading && (
                <svg className="animate-spin -ml-1 mr-2 h-5 w-5 text-white" xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24">
                  <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4"></circle>
                  <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"></path>
                </svg>
              )}
              {!loading && '🔓 获取受保护资源'}
              {loading && !activeTest && '⏳ 获取中...'}
            </div>
          </button>
        </div>

        {/* 测试结果区域 */}
        <div className="space-y-6">
          {/* 错误显示 */}
          {error && (
            <div className="bg-red-50 border border-red-200 rounded-xl shadow-sm p-6">
              <div>
                <h3 className="text-lg font-semibold text-red-800 mb-2">❌ 测试失败</h3>
                <div className="bg-white rounded-lg p-4 border border-red-100">
                  <pre className="text-sm text-red-700 whitespace-pre-wrap">{error}</pre>
                </div>
              </div>
            </div>
          )}

          {/* 成功响应显示 */}
          {resourceData && testStatus === 'success' && (
            <div className="bg-green-50 border border-green-200 rounded-xl shadow-sm p-6">
              <div>
                <h3 className="text-lg font-semibold text-green-800 mb-2">✅ 测试成功</h3>
                <div className="bg-white rounded-lg p-4 border border-green-100 shadow-sm relative" style={{ textAlign: 'left' }}>
                  <div className="flex justify-between items-center mb-2">
                    <span className="text-xs text-gray-500">JSON 响应</span>
                    <button
                      onClick={() => copyToClipboard(JSON.stringify(resourceData, null, 2))}
                      className="text-xs px-3 py-1 bg-gray-100 hover:bg-gray-200 rounded-md transition-colors flex items-center gap-1"
                    >
                      {copySuccess ? '✅ 已拷贝' : '📋 拷贝'}
                    </button>
                  </div>
                  <pre className="text-sm text-gray-800 whitespace-pre-wrap overflow-x-auto max-h-96 bg-gray-50 p-4 rounded-md font-mono" style={{ textAlign: 'left', display: 'block' }}>
                    {JSON.stringify(resourceData, null, 2)}
                  </pre>
                </div>
              </div>
            </div>
          )}

          {/* 测试进行中 */}
          {loading && (
            <div className="bg-yellow-50 border border-yellow-200 rounded-xl shadow-sm p-6">
              <div>
                <h3 className="text-lg font-semibold text-yellow-800 mb-2">⏳ 测试进行中</h3>
                <p className="text-yellow-700">请稍候，正在发送请求...</p>
              </div>
            </div>
          )}

          {/* 初始状态信息 */}
          {!resourceData && !error && !loading && testStatus === 'idle' && (
            <div className="bg-gray-50 border border-gray-200 rounded-xl shadow-sm p-6">
              <div>
                <h3 className="text-lg font-semibold text-gray-800 mb-2">ℹ️ 就绪</h3>
                <p className="text-gray-600">点击上方按钮开始测试异构资源服务器集成</p>
              </div>
            </div>
          )}
        </div>

        {/* 集成流程说明 */}
        <div className="mt-10 bg-white rounded-xl shadow-md p-8 border border-gray-200">
          <h2 className="text-2xl font-bold text-gray-800 mb-6">🔄 集成流程</h2>
          
          <div className="relative">
            {/* 连接线 */}
            <div className="absolute left-4 top-12 bottom-12 w-0.5 bg-blue-200 hidden md:block"></div>
            
            <div className="space-y-8">
              <div className="flex gap-6">
                <div className="flex-shrink-0 w-10 h-10 bg-blue-500 text-white rounded-full flex items-center justify-center font-bold shadow-md z-10">1</div>
                <div className="flex-1 bg-blue-50 rounded-lg p-5">
                  <h3 className="font-semibold text-blue-900 text-lg mb-2">用户登录</h3>
                  <p className="text-gray-700">用户在 Spring Boot 应用中登录，获得 JWT Token</p>
                </div>
              </div>

              <div className="flex gap-6">
                <div className="flex-shrink-0 w-10 h-10 bg-blue-500 text-white rounded-full flex items-center justify-center font-bold shadow-md z-10">2</div>
                <div className="flex-1 bg-blue-50 rounded-lg p-5">
                  <h3 className="font-semibold text-blue-900 text-lg mb-2">Token 存储</h3>
                  <p className="text-gray-700">Token 安全存储在浏览器 localStorage 中</p>
                </div>
              </div>

              <div className="flex gap-6">
                <div className="flex-shrink-0 w-10 h-10 bg-blue-500 text-white rounded-full flex items-center justify-center font-bold shadow-md z-10">3</div>
                <div className="flex-1 bg-blue-50 rounded-lg p-5">
                  <h3 className="font-semibold text-blue-900 text-lg mb-2">获取公钥</h3>
                  <p className="text-gray-700">Python 资源服务器从 JWKS 端点获取认证服务器的公钥</p>
                </div>
              </div>

              <div className="flex gap-6">
                <div className="flex-shrink-0 w-10 h-10 bg-blue-500 text-white rounded-full flex items-center justify-center font-bold shadow-md z-10">4</div>
                <div className="flex-1 bg-blue-50 rounded-lg p-5">
                  <h3 className="font-semibold text-blue-900 text-lg mb-2">验证 Token</h3>
                  <p className="text-gray-700">Python 资源服务器使用公钥验证 Token 签名和有效性</p>
                </div>
              </div>

              <div className="flex gap-6">
                <div className="flex-shrink-0 w-10 h-10 bg-blue-500 text-white rounded-full flex items-center justify-center font-bold shadow-md z-10">5</div>
                <div className="flex-1 bg-blue-50 rounded-lg p-5">
                  <h3 className="font-semibold text-blue-900 text-lg mb-2">访问资源</h3>
                  <p className="text-gray-700">验证成功后，前端可以安全访问 Python 资源服务器的受保护资源</p>
                </div>
              </div>
            </div>
          </div>
        </div>

        {/* 页脚 */}
        <footer className="mt-12 text-gray-500 text-sm">
          <p>© 2026 异构资源服务器集成测试 | 版本 1.0.0</p>
        </footer>
      </div>
    </div>
  );
};

export default ResourceTestPage;

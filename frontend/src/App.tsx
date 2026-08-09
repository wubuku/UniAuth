import { Suspense } from 'react';
import { BrowserRouter as Router, Routes, Route, Navigate } from 'react-router-dom';
import HomePage from './pages/HomePage';
import LoginPage from './pages/LoginPage';
import OAuth2CallbackPage from './pages/OAuth2CallbackPage';
import {
  ResourceTestPage,
  TestPage,
} from 'virtual:diagnostics-routes';
import './App.css';

function AppContent() {
  return (
    <div style={{ minHeight: '100vh', backgroundColor: '#f5f5f5' }}>
      <Routes>
        {/* 公开路由 */}
        <Route path="/" element={<HomePage />} />
        <Route path="/login" element={<LoginPage />} />

        {/* 受保护路由 */}
        {TestPage && <Route path="/test" element={<TestPage />} />}
        {ResourceTestPage && (
          <Route path="/resource-test" element={<ResourceTestPage />} />
        )}

        {/* OAuth2回调路由 */}
        <Route path="/oauth2/callback" element={<OAuth2CallbackPage />} />

        {/* 404页面 */}
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </div>
  );
}

function App() {
  return (
    <Router>
      <Suspense fallback={null}>
        <AppContent />
      </Suspense>
    </Router>
  );
}

export default App;

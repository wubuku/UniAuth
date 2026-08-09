import { defineConfig, type Plugin } from 'vite'
import react from '@vitejs/plugin-react'

const devProxyTarget = process.env.VITE_DEV_PROXY_TARGET || 'http://localhost:8081'
const devProxyOrigin = new URL(devProxyTarget).origin
const diagnosticsModuleId = 'virtual:diagnostics-routes'
const resolvedDiagnosticsModuleId = `\0${diagnosticsModuleId}`

function diagnosticsRoutesPlugin(devServer: boolean): Plugin {
  return {
    name: 'diagnostics-routes',
    resolveId(source) {
      return source === diagnosticsModuleId
        ? resolvedDiagnosticsModuleId
        : null
    },
    load(id) {
      if (id !== resolvedDiagnosticsModuleId) {
        return null
      }
      if (!devServer) {
        return `
          export const diagnosticsEnabled = false;
          export const TestPage = null;
          export const ResourceTestPage = null;
          export const DiagnosticsHomeLinks = null;
        `
      }
      return `
        import { lazy } from 'react';
        export const diagnosticsEnabled =
          import.meta.env.VITE_AUTH_DIAGNOSTICS === 'true';
        export const TestPage = diagnosticsEnabled
          ? lazy(() => import('/src/pages/TestPage.tsx'))
          : null;
        export const ResourceTestPage = diagnosticsEnabled
          ? lazy(() => import('/src/pages/ResourceTestPage.tsx'))
          : null;
        export const DiagnosticsHomeLinks = diagnosticsEnabled
          ? lazy(() => import('/src/components/DiagnosticsHomeLinks.tsx'))
          : null;
      `
    },
  }
}

// https://vitejs.dev/config/
export default defineConfig(({ command }) => ({
  plugins: [
    react(),
    diagnosticsRoutesPlugin(command === 'serve'),
  ],
  server: {
    port: 5173,
    allowedHosts: ['api.u2511175.nyat.app', 'localhost', '127.0.0.1', 'blacksheepmedia.xyz'],
    proxy: {
      // 开发环境代理API请求到后端
      '/api': {
        target: devProxyTarget,
        changeOrigin: true,
        secure: false,
        headers: {
          Origin: devProxyOrigin,
        },
      },
      '/oauth2': {
        target: devProxyTarget,
        changeOrigin: true,
        secure: false,
        headers: {
          Origin: devProxyOrigin,
        },
      }
    }
  },
  build: {
    outDir: '../src/main/resources/static', // 构建到Spring Boot静态资源目录
    emptyOutDir: true,
    sourcemap: false, // 生产环境不需要sourcemap
    rollupOptions: {
      output: {
        manualChunks: undefined, // 简化chunk分割
      }
    }
  },
  // 生产环境API路径配置
  define: {
    'import.meta.env.VITE_API_BASE_URL': JSON.stringify(''), // 使用相对路径，指向同一域的API
  }
}))

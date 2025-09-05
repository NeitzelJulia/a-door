import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const devIP = env.VITE_DEV_IP || '127.0.0.1'

  return {
    plugins: [react()],
    server: {
      host: devIP,
      port: 5174,
      strictPort: true,
      hmr: { host: devIP },
      proxy: {
        '/api': {
          target: 'http://localhost:8081',
          changeOrigin: true
        }
      }
    }
  }
})

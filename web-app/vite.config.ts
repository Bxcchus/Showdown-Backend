import { sites } from '@openai/sites-vite-plugin'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

export default defineConfig({
  plugins: [react(), sites()],
  server: {
    proxy: {
      '/backend': {
        target: 'http://localhost:8088',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/backend/, ''),
      },
    },
  },
})

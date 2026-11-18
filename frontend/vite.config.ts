import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 18179,
    proxy: {
      '/api': {
        target: 'http://localhost:18178',
        changeOrigin: true,
      },
    },
  },
});

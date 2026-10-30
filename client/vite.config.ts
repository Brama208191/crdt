import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// In dev, the Spring Boot server runs on :8080 and Vite proxies API and WebSocket traffic to it.
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': 'http://localhost:8080',
      '/ws': { target: 'ws://localhost:8080', ws: true },
    },
  },
});

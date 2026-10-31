/// <reference types="vitest/config" />
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

// The browser only ever talks to this origin. /api is proxied to the API gateway
// (the gateway has no CORS configuration, so a cross-origin call would be refused).
const gateway = process.env.TRADEFLOW_GATEWAY_URL ?? 'http://localhost:8000';

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    proxy: { '/api': { target: gateway, changeOrigin: true, xfwd: true } },
  },
  preview: {
    port: 4173,
    proxy: { '/api': { target: gateway, changeOrigin: true, xfwd: true } },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test-setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
  },
});

import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import { fileURLToPath } from 'node:url';

export default defineConfig(({ command }) => ({
  base: './',
  plugins: [react()],
  resolve: {
    alias: {
      '@implicit/compiler': fileURLToPath(
        new URL(`.compiler/${command === 'build' ? 'production' : 'development'}/main.js`, import.meta.url),
      ),
    },
  },
  define: { global: 'globalThis' },
  server: { fs: { allow: ['..'] } },
  worker: { format: 'es' },
  build: { target: 'es2022' },
  test: { include: ['src/**/*.test.ts'], testTimeout: 30_000 },
}));

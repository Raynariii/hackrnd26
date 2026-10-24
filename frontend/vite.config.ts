import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  build: {
    // Monaco (~4 МБ) — отдельным чанком: основной бандл приложения остаётся маленьким.
    chunkSizeWarningLimit: 5000,
    rollupOptions: {
      output: {
        manualChunks: (id) => (id.includes('node_modules/monaco-editor') ? 'monaco' : undefined),
      },
    },
  },
  server: {
    port: 5173,
    proxy: { '/api': 'http://localhost:8080' },
  },
});

import { resolve } from 'node:path';
import { defineConfig } from 'vite';

export default defineConfig({
  base: '/databroadcast/',
  define: {
    global: 'globalThis',
  },
  worker: {
    format: 'es',
    rollupOptions: {
      output: {
        entryFileNames: 'b24-worker.js',
      },
    },
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    target: 'es2020',
    assetsInlineLimit: 0,
    rollupOptions: {
      input: {
        b24: resolve(__dirname, 'b24.html'),
        b60: resolve(__dirname, 'b60.html'),
      },
      output: {
        entryFileNames: '[name].js',
        chunkFileNames: 'chunk-[name].js',
        assetFileNames: '[name][extname]',
      },
    },
  },
});

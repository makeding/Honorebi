import { defineConfig } from 'vite';

export default defineConfig({
  base: '/web-bml/',
  define: {
    global: 'globalThis',
  },
  worker: {
    format: 'es',
    rollupOptions: {
      output: {
        entryFileNames: 'web-bml-worker.js',
      },
    },
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    target: 'es2020',
    assetsInlineLimit: 0,
    rollupOptions: {
      output: {
        entryFileNames: 'web-bml.js',
        chunkFileNames: 'web-bml-[name].js',
        assetFileNames: '[name][extname]',
      },
    },
  },
});

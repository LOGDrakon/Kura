import { svelte } from '@sveltejs/vite-plugin-svelte';
import { defineConfig } from 'vite';
import { viteSingleFile } from 'vite-plugin-singlefile';

// Kura Studio est livré en un seul fichier HTML autonome (ouvrable en local, partageable par mail),
// ce qui permet d'y embarquer les modèles de l'entreprise.
export default defineConfig({
  base: './',
  plugins: [svelte(), viteSingleFile()],
  build: { target: 'es2022', assetsInlineLimit: 100_000_000, chunkSizeWarningLimit: 5000 },
  server: { fs: { allow: ['../..'] } },
  test: { environment: 'node', include: ['src/**/*.test.ts'] },
});

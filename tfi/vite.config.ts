import { defineConfig } from 'vite';
import preact from '@preact/preset-vite';

// The read-only GTFS API the dev server proxies to. Defaults to production, which is safe: every
// endpoint the client uses is a GET.
const upstream = process.env.GTFSR_STOP_TIMES_URL ?? 'https://iompar.mpts.ie/gtfsr-stop-times';

export default defineConfig({
	plugins: [preact()],
	// Relative asset URLs, so the built site works at / or behind any path prefix.
	base: './',
	build: {
		target: 'es2022',
		// Leaflet is ~150 kB and only the stop and trip maps need it; it is split out by the
		// dynamic import in Map.tsx, so the first paint of the home screen doesn't wait on it.
		chunkSizeWarningLimit: 200,
	},
	server: {
		proxy: {
			'/api': {
				target: upstream,
				changeOrigin: true,
				rewrite: (p) => p.replace(/^\/api/, ''),
			},
		},
	},
});

<script lang="ts">
	import { page } from '$app/stores';
	import type { Snippet } from 'svelte';

	interface Props {
		children: Snippet;
	}
	const { children }: Props = $props();
	const isHome = $derived($page.url.pathname === '/');
</script>

<svelte:head>
	<meta name="theme-color" content="#16a34a" />
</svelte:head>

<div class="shell">
	<header class="site-header">
		<a href="/" class="logo" class:home={isHome}>
			<span class="logo-badge">&#9752;</span>
			<span class="logo-text">clover</span>
		</a>
		<nav class="site-nav">
			<a href="/">Markets</a>
			<a href="/stop">Stops</a>
		</nav>
	</header>

	<main class="site-main">
		{@render children()}
	</main>

	<footer class="site-footer">
		<p>
			Replayed data from <a href="https://developer.nationaltransport.ie/" target="_blank" rel="noopener">NTA GTFS-R</a>.
			Not affiliated with Dublin Bus or TFI. For fun only.
		</p>
	</footer>
</div>

<style>
	:global(*) {
		box-sizing: border-box;
	}
	:global(:root) {
		--clover: #16a34a;
		--clover-dark: #15803d;
		--clover-light: #22c55e;
		--gold: #eab308;
		--bg: #f0fdf4;
		font-family: system-ui, -apple-system, 'Segoe UI', sans-serif;
	}
	:global(body) {
		margin: 0;
		background: var(--bg);
		color: #111;
		min-height: 100dvh;
	}
	:global(a) {
		color: var(--clover-dark);
	}

	.shell {
		display: flex;
		flex-direction: column;
		min-height: 100dvh;
	}

	.site-header {
		background: var(--clover);
		padding: 0.7rem 1.5rem;
		display: flex;
		align-items: center;
		justify-content: space-between;
		border-bottom: 3px solid var(--gold);
	}

	.logo {
		display: flex;
		align-items: center;
		gap: 0.5rem;
		text-decoration: none;
	}
	.logo-badge {
		font-size: 1.4rem;
		line-height: 1;
	}
	.logo-text {
		color: #fff;
		font-weight: 700;
		font-size: 1.2rem;
		letter-spacing: 0.02em;
	}

	.site-nav {
		display: flex;
		align-items: center;
		gap: 1.2rem;
	}
	.site-nav a {
		color: rgba(255, 255, 255, 0.85);
		text-decoration: none;
		font-size: 0.9rem;
		font-weight: 500;
	}
	.site-nav a:hover {
		color: var(--gold);
	}

	.site-main {
		flex: 1;
		display: flex;
		flex-direction: column;
		align-items: center;
		padding: 2rem 1rem;
	}

	.site-footer {
		background: var(--clover-dark);
		color: rgba(255, 255, 255, 0.55);
		font-size: 0.78rem;
		text-align: center;
		padding: 0.8rem 1rem;
		border-top: 2px solid var(--gold);
	}
	.site-footer a {
		color: var(--gold);
	}
	.site-footer p {
		margin: 0;
	}
</style>

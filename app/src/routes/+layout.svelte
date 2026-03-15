<script lang="ts">
	import { page } from '$app/stores';
	import type { Snippet } from 'svelte';

	interface Props {
		children: Snippet;
		data: { balance: number };
	}
	const { children, data }: Props = $props();
	const isHome = $derived($page.url.pathname === '/');
	const balance = $derived(data.balance);
</script>

<svelte:head>
	<meta name="theme-color" content="#003B8C" />
</svelte:head>

<div class="shell">
	<header class="site-header">
		<a href="/" class="logo" class:home={isHome}>
			<span class="logo-badge">DB</span>
			<span class="logo-text">BusBet</span>
		</a>
		<nav class="site-nav">
			<a href="/">Stops</a>
			<span class="balance-chip">€{balance.toFixed(2)}</span>
		</nav>
	</header>

	<main class="site-main">
		{@render children()}
	</main>

	<footer class="site-footer">
		<p>
			Live data from <a href="https://developer.nationaltransport.ie/" target="_blank" rel="noopener">NTA GTFS-R</a>.
			Not affiliated with Dublin Bus or Transport for Ireland.
		</p>
	</footer>
</div>

<style>
	:global(*) {
		box-sizing: border-box;
	}
	:global(:root) {
		--db-yellow: #ffd200;
		--db-blue: #003b8c;
		--db-blue-light: #1a5cbf;
		--bg: #eef1f7;
		font-family: system-ui, -apple-system, 'Segoe UI', sans-serif;
	}
	:global(body) {
		margin: 0;
		background: var(--bg);
		color: #111;
		min-height: 100dvh;
	}
	:global(a) {
		color: var(--db-blue);
	}

	.shell {
		display: flex;
		flex-direction: column;
		min-height: 100dvh;
	}

	.site-header {
		background: var(--db-blue);
		padding: 0.7rem 1.5rem;
		display: flex;
		align-items: center;
		justify-content: space-between;
		border-bottom: 3px solid var(--db-yellow);
	}

	.logo {
		display: flex;
		align-items: center;
		gap: 0.6rem;
		text-decoration: none;
	}
	.logo-badge {
		background: var(--db-yellow);
		color: var(--db-blue);
		font-weight: 800;
		font-size: 1rem;
		padding: 0.2rem 0.45rem;
		border-radius: 2px;
		letter-spacing: 0.04em;
	}
	.logo-text {
		color: #fff;
		font-weight: 700;
		font-size: 1.1rem;
		letter-spacing: 0.02em;
	}

	.site-nav {
		display: flex;
		align-items: center;
		gap: 1rem;
	}
	.site-nav a {
		color: rgba(255, 255, 255, 0.8);
		text-decoration: none;
		font-size: 0.9rem;
		font-weight: 500;
	}
	.site-nav a:hover {
		color: var(--db-yellow);
	}
	.balance-chip {
		background: var(--db-yellow);
		color: var(--db-blue);
		font-size: 0.8rem;
		font-weight: 800;
		padding: 0.18rem 0.55rem;
		border-radius: 2px;
		font-variant-numeric: tabular-nums;
		letter-spacing: 0.02em;
	}

	.site-main {
		flex: 1;
		display: flex;
		flex-direction: column;
		align-items: center;
		padding: 2rem 1rem;
	}

	.site-footer {
		background: var(--db-blue);
		color: rgba(255, 255, 255, 0.55);
		font-size: 0.78rem;
		text-align: center;
		padding: 0.8rem 1rem;
		border-top: 2px solid var(--db-yellow);
	}
	.site-footer a {
		color: var(--db-yellow);
	}
	.site-footer p {
		margin: 0;
	}
</style>

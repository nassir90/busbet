<script lang="ts">
	import { goto } from '$app/navigation';
	import StopSearch from '$lib/components/StopSearch.svelte';
	import type { Stop } from '$lib/server/storage/types.js';
	import type { PageData } from './$types';

	const { data }: { data: PageData } = $props();

	function onStopSelected(stop: Stop) {
		goto(`/stop/${stop.stop_code}`);
	}
</script>

<svelte:head>
	<title>BusBet — Dublin Bus Live Departures</title>
</svelte:head>

<section class="hero">
	<div class="hero-inner">
		<div class="hero-brand">
			<span class="hero-badge">DB</span>
			<div>
				<h1 class="hero-title">BusBet</h1>
				<p class="hero-subtitle">Live Dublin Bus departure boards</p>
			</div>
		</div>

		{#if !data.seeded}
			<div class="warning-banner">
				<strong>GTFS data not loaded.</strong>
				Run <code>npm run load-gtfs</code> to import stop and timetable data.
			</div>
		{:else}
			<StopSearch onSelect={onStopSelected} />
		{/if}
	</div>
</section>

<section class="how">
	<div class="how-inner">
		<h2>How it works</h2>
		<ol>
			<li>Search for your bus stop by name or stop number.</li>
			<li>See all upcoming buses in the next 90 minutes.</li>
			<li>Live delay data from the NTA GTFS-R feed, refreshed every 30 seconds.</li>
		</ol>
	</div>
</section>

<style>
	.hero {
		width: 100%;
		display: flex;
		justify-content: center;
		padding: 3rem 1rem 2rem;
	}
	.hero-inner {
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: 2rem;
		max-width: 580px;
		width: 100%;
	}

	.hero-brand {
		display: flex;
		align-items: center;
		gap: 1.2rem;
	}
	.hero-badge {
		background: var(--db-yellow);
		color: var(--db-blue);
		font-weight: 900;
		font-size: 2.2rem;
		padding: 0.3rem 0.7rem;
		border-radius: 3px;
		letter-spacing: 0.03em;
		line-height: 1;
	}
	.hero-title {
		margin: 0;
		font-size: 2.4rem;
		font-weight: 800;
		color: var(--db-blue);
		letter-spacing: -0.02em;
		line-height: 1;
	}
	.hero-subtitle {
		margin: 0.25rem 0 0;
		color: #666;
		font-size: 1rem;
	}

	.warning-banner {
		background: #fff3cd;
		border: 1px solid #f59e0b;
		border-left: 4px solid #f59e0b;
		border-radius: 2px;
		padding: 0.75rem 1rem;
		font-size: 0.9rem;
		color: #92400e;
		max-width: 520px;
		width: 100%;
	}
	.warning-banner code {
		background: rgba(0, 0, 0, 0.08);
		padding: 0.1em 0.35em;
		border-radius: 2px;
		font-size: 0.85em;
	}

	.how {
		width: 100%;
		max-width: 580px;
		padding: 0 1rem;
	}
	.how-inner {
		background: #fff;
		border: 1px solid #d1daea;
		border-radius: 2px;
		padding: 1.2rem 1.5rem;
	}
	.how-inner h2 {
		margin: 0 0 0.75rem;
		font-size: 0.85rem;
		font-weight: 700;
		text-transform: uppercase;
		letter-spacing: 0.08em;
		color: var(--db-blue);
	}
	.how-inner ol {
		margin: 0;
		padding-left: 1.2rem;
		color: #444;
		font-size: 0.9rem;
		line-height: 1.7;
	}
</style>

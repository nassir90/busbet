<script lang="ts">
	import type { Departure } from '$lib/server/storage/types.js';
	import type { Stop } from '$lib/server/storage/types.js';
	import DepartureCard from './DepartureCard.svelte';

	interface Props {
		stop: Stop;
		initialDepartures: Departure[];
	}
	const { stop, initialDepartures }: Props = $props();

	let departures = $state<Departure[]>([]);
	let lastRefreshed = $state(new Date());
	let refreshing = $state(false);

	$effect(() => {
		departures = [...initialDepartures];
	});

	async function refresh() {
		refreshing = true;
		try {
			const res = await fetch(`/api/departures/${stop.stop_code}`);
			if (res.ok) {
				const data = await res.json();
				departures = data.departures;
				lastRefreshed = new Date();
			}
		} finally {
			refreshing = false;
		}
	}

	$effect(() => {
		const interval = setInterval(refresh, 30_000);
		return () => clearInterval(interval);
	});

	const timeLabel = $derived(
		lastRefreshed.toLocaleTimeString('en-IE', { hour: '2-digit', minute: '2-digit' })
	);
</script>

<section class="board">
	<header class="board-header">
		<div class="stop-info">
			<span class="stop-num">{stop.stop_code}</span>
			<div>
				<h1 class="stop-name">{stop.stop_name}</h1>
				<p class="refresh-note">
					Updated {timeLabel}
					{#if refreshing}<span class="live-dot" title="Refreshing..."></span>{/if}
				</p>
			</div>
		</div>
		<button class="refresh-btn" onclick={refresh} disabled={refreshing} title="Refresh">
			<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
				<path d="M23 4v6h-6"/>
				<path d="M1 20v-6h6"/>
				<path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15"/>
			</svg>
		</button>
	</header>

	{#if departures.length === 0}
		<div class="empty">
			<p>No departures in the next 90 minutes.</p>
		</div>
	{:else}
		<ul class="departure-list">
			{#each departures as dep (dep.trip_id)}
				<li>
					<DepartureCard departure={dep} />
				</li>
			{/each}
		</ul>
	{/if}
</section>

<style>
	.board {
		max-width: 680px;
		width: 100%;
	}

	.board-header {
		display: flex;
		align-items: center;
		justify-content: space-between;
		background: var(--db-blue);
		color: #fff;
		padding: 1rem 1.2rem;
		border-radius: 2px 2px 0 0;
		margin-bottom: 0;
	}

	.stop-info {
		display: flex;
		align-items: center;
		gap: 1rem;
	}

	.stop-num {
		display: inline-flex;
		align-items: center;
		justify-content: center;
		min-width: 3.4rem;
		height: 3.4rem;
		background: var(--db-yellow);
		color: var(--db-blue);
		font-weight: 800;
		font-size: 1rem;
		border-radius: 2px;
		flex-shrink: 0;
		letter-spacing: 0.04em;
	}

	.stop-name {
		margin: 0;
		font-size: 1.15rem;
		font-weight: 700;
		color: var(--db-yellow);
	}

	.refresh-note {
		margin: 0.15rem 0 0;
		font-size: 0.75rem;
		color: rgba(255, 255, 255, 0.7);
		display: flex;
		align-items: center;
		gap: 0.4rem;
	}

	.live-dot {
		display: inline-block;
		width: 7px;
		height: 7px;
		background: var(--db-yellow);
		border-radius: 50%;
		animation: pulse 1s infinite;
	}
	@keyframes pulse {
		0%, 100% { opacity: 1; }
		50% { opacity: 0.3; }
	}

	.refresh-btn {
		background: rgba(255, 255, 255, 0.12);
		border: 1px solid rgba(255, 255, 255, 0.3);
		color: #fff;
		border-radius: 2px;
		padding: 0.45rem;
		cursor: pointer;
		display: flex;
		align-items: center;
		justify-content: center;
		transition: background 0.15s;
	}
	.refresh-btn:hover:not(:disabled) {
		background: rgba(255, 255, 255, 0.22);
	}
	.refresh-btn:disabled {
		opacity: 0.5;
		cursor: default;
	}

	.departure-list {
		list-style: none;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: 2px;
		background: #e8edf4;
		border: 1px solid #c5cfdf;
		border-top: none;
		border-radius: 0 0 2px 2px;
	}

	.departure-list li:first-child :global(.card) {
		border-radius: 0;
	}
	.departure-list li:last-child :global(.card) {
		border-radius: 0 0 2px 2px;
	}

	.empty {
		background: #fff;
		border: 1px solid #c5cfdf;
		border-top: none;
		border-radius: 0 0 2px 2px;
		padding: 2rem;
		text-align: center;
		color: #666;
		font-size: 0.95rem;
	}
</style>

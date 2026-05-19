<script lang="ts">
	import type { PageData } from './$types';
	import DepartureCard from '$lib/components/DepartureCard.svelte';

	const { data }: { data: PageData } = $props();

	const nextDep = $derived(data.departures[0] ?? null);
	const delayMins = $derived(
		nextDep?.delay_seconds != null ? Math.round(nextDep.delay_seconds / 60) : null
	);
</script>

<svelte:head>
	<title>{data.market.route_short_name} {data.market.direction} — clover</title>
</svelte:head>

<div class="market-page">
	<header class="market-header">
		<span class="route-badge">{data.market.route_short_name}</span>
		<div>
			<h1 class="market-title">{data.market.stop_name} &#8594; {data.market.direction}</h1>
			<p class="market-desc">{data.market.description}</p>
		</div>
	</header>

	{#if nextDep}
		<section class="prediction-panel">
			<h2 class="panel-title">Next departure</h2>
			<div class="prediction-bar">
				<div class="pred-time">
					<span class="sched">{nextDep.scheduled_departure}</span>
					{#if nextDep.realtime && nextDep.estimated_departure}
						<span class="arrow">&#8594;</span>
						<span class="est" class:late={delayMins != null && delayMins > 1}>
							{nextDep.estimated_departure}
						</span>
					{/if}
				</div>
				<div class="pred-status">
					{#if !nextDep.realtime}
						<span class="badge scheduled">Scheduled</span>
					{:else if delayMins != null && delayMins <= 1}
						<span class="badge on-time">On time</span>
					{:else if delayMins != null && delayMins <= 5}
						<span class="badge minor">+{delayMins} min</span>
					{:else if delayMins != null}
						<span class="badge major">+{delayMins} min</span>
					{/if}
				</div>
			</div>
			{#if nextDep.realtime && nextDep.delay_seconds != null}
				<div class="delay-bar-wrapper">
					<div class="delay-bar-track">
						<div
							class="delay-bar-fill"
							class:on-time={nextDep.delay_seconds <= 60}
							class:minor={nextDep.delay_seconds > 60 && nextDep.delay_seconds <= 300}
							class:major={nextDep.delay_seconds > 300}
							style="width: {Math.min(100, Math.abs(nextDep.delay_seconds) / 6)}%"
						></div>
					</div>
					<span class="delay-label">
						{nextDep.delay_seconds > 0 ? '+' : ''}{Math.round(nextDep.delay_seconds / 60)} min delay
					</span>
				</div>
			{/if}
		</section>
	{/if}

	<section class="upcoming">
		<h2 class="section-title">Upcoming {data.market.route_short_name} departures</h2>
		{#if data.departures.length === 0}
			<p class="empty">No {data.market.route_short_name} departures in the next 90 minutes.</p>
		{:else}
			<ul class="dep-list">
				{#each data.departures as dep (dep.trip_id)}
					<li>
						<DepartureCard departure={dep} />
					</li>
				{/each}
			</ul>
		{/if}
	</section>

	<section class="all-departures">
		<h2 class="section-title">All departures from {data.stop.stop_name}</h2>
		{#if data.allDepartures.length === 0}
			<p class="empty">No departures found.</p>
		{:else}
			<ul class="dep-list">
				{#each data.allDepartures as dep (dep.trip_id)}
					<li>
						<DepartureCard departure={dep} />
					</li>
				{/each}
			</ul>
		{/if}
	</section>
</div>

<style>
	.market-page {
		max-width: 680px;
		width: 100%;
		display: flex;
		flex-direction: column;
		gap: 1.5rem;
	}

	.market-header {
		display: flex;
		align-items: center;
		gap: 1rem;
		background: var(--clover);
		color: #fff;
		padding: 1.2rem 1.5rem;
		border-radius: 4px;
	}

	.route-badge {
		display: inline-flex;
		align-items: center;
		justify-content: center;
		min-width: 3.5rem;
		height: 3rem;
		padding: 0 0.6rem;
		background: rgba(255, 255, 255, 0.2);
		color: #fff;
		font-size: 1.3rem;
		font-weight: 800;
		border-radius: 4px;
		flex-shrink: 0;
	}

	.market-title {
		margin: 0;
		font-size: 1.2rem;
		font-weight: 700;
	}
	.market-desc {
		margin: 0.2rem 0 0;
		font-size: 0.8rem;
		opacity: 0.8;
	}

	.prediction-panel {
		background: #fff;
		border: 1px solid #d1e7dd;
		border-radius: 4px;
		padding: 1.2rem;
	}

	.panel-title {
		margin: 0 0 0.8rem;
		font-size: 0.75rem;
		font-weight: 700;
		text-transform: uppercase;
		letter-spacing: 0.1em;
		color: var(--clover-dark);
	}

	.prediction-bar {
		display: flex;
		align-items: center;
		justify-content: space-between;
	}

	.pred-time {
		display: flex;
		align-items: baseline;
		gap: 0.5rem;
		font-variant-numeric: tabular-nums;
	}
	.sched {
		font-size: 1.8rem;
		font-weight: 800;
		color: var(--clover-dark);
	}
	.arrow {
		color: #999;
		font-size: 1rem;
	}
	.est {
		font-size: 1.4rem;
		font-weight: 700;
		color: var(--clover);
	}
	.est.late {
		color: #ef4444;
	}

	.badge {
		font-size: 0.75rem;
		font-weight: 600;
		text-transform: uppercase;
		letter-spacing: 0.06em;
		padding: 0.2rem 0.5rem;
		border-radius: 3px;
	}
	.badge.scheduled {
		background: #f1f5f9;
		color: #64748b;
	}
	.badge.on-time {
		background: #d1fae5;
		color: #065f46;
	}
	.badge.minor {
		background: #fef3c7;
		color: #92400e;
	}
	.badge.major {
		background: #fee2e2;
		color: #991b1b;
	}

	.delay-bar-wrapper {
		margin-top: 0.8rem;
		display: flex;
		align-items: center;
		gap: 0.6rem;
	}
	.delay-bar-track {
		flex: 1;
		height: 6px;
		background: #e5e7eb;
		border-radius: 3px;
		overflow: hidden;
	}
	.delay-bar-fill {
		height: 100%;
		border-radius: 3px;
		transition: width 0.3s;
	}
	.delay-bar-fill.on-time {
		background: #22c55e;
	}
	.delay-bar-fill.minor {
		background: #f59e0b;
	}
	.delay-bar-fill.major {
		background: #ef4444;
	}
	.delay-label {
		font-size: 0.75rem;
		color: #888;
		font-variant-numeric: tabular-nums;
		white-space: nowrap;
	}

	.section-title {
		font-size: 0.75rem;
		font-weight: 700;
		text-transform: uppercase;
		letter-spacing: 0.1em;
		color: var(--clover-dark);
		margin: 0 0 0.6rem;
	}

	.dep-list {
		list-style: none;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: 2px;
		background: #e8f5e9;
		border: 1px solid #c8e6c9;
		border-radius: 4px;
	}

	.empty {
		color: #888;
		font-size: 0.9rem;
		text-align: center;
		padding: 2rem;
		background: #fff;
		border: 1px solid #d1e7dd;
		border-radius: 4px;
	}
</style>

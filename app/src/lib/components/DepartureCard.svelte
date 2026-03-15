<script lang="ts">
	import type { Departure } from '$lib/server/storage/types.js';
	import WagerForm from './WagerForm.svelte';

	interface Props {
		departure: Departure;
		stopId: string;
		onBalanceUpdate: (balance: number) => void;
	}
	const { departure, stopId, onBalanceUpdate }: Props = $props();

	const delayMins = $derived(
		departure.delay_seconds != null ? Math.round(departure.delay_seconds / 60) : null
	);

	const statusClass = $derived(() => {
		if (delayMins == null) return 'on-time';
		if (delayMins <= 1) return 'on-time';
		if (delayMins <= 5) return 'minor-delay';
		return 'major-delay';
	});

	const statusLabel = $derived(() => {
		if (delayMins == null) return 'scheduled';
		if (delayMins <= 0) return 'on time';
		return `+${delayMins} min`;
	});

	function minutesUntil(timeHHMM: string): number {
		const [h, m] = timeHHMM.split(':').map(Number);
		const now = new Date();
		const diff = h * 60 + m - (now.getHours() * 60 + now.getMinutes());
		return diff;
	}

	const minsUntil = $derived(
		minutesUntil(departure.estimated_departure ?? departure.scheduled_departure)
	);
</script>

<article class="card {statusClass()}">
	<div class="card-route">
		<span class="route-badge">{departure.route_short_name}</span>
	</div>
	<div class="card-body">
		<p class="headsign">{departure.trip_headsign}</p>
		<div class="times">
			<span class="time scheduled">{departure.scheduled_departure}</span>
			{#if departure.realtime && departure.estimated_departure}
				<span class="time estimated">{departure.estimated_departure}</span>
			{/if}
		</div>
		<WagerForm
			tripId={departure.trip_id}
			{stopId}
			scheduledDeparture={departure.scheduled_departure}
			onWagerPlaced={onBalanceUpdate}
		/>
	</div>
	<div class="card-status">
		<span class="status-badge {statusClass()}">{statusLabel()}</span>
		<span class="due">
			{#if minsUntil <= 1}
				due
			{:else}
				{minsUntil} min
			{/if}
		</span>
	</div>
</article>

<style>
	.card {
		display: grid;
		grid-template-columns: 4.5rem 1fr auto;
		align-items: start;
		gap: 1rem;
		padding: 0.9rem 1rem;
		background: #fff;
		border-left: 4px solid var(--db-yellow);
		border-radius: 2px;
		box-shadow: 0 1px 4px rgba(0, 0, 0, 0.08);
		transition: box-shadow 0.15s;
	}
	.card:hover {
		box-shadow: 0 3px 10px rgba(0, 0, 0, 0.14);
	}
	.card.minor-delay {
		border-left-color: #f59e0b;
	}
	.card.major-delay {
		border-left-color: #ef4444;
	}

	.card-route {
		padding-top: 0.1rem;
	}

	.route-badge {
		display: inline-flex;
		align-items: center;
		justify-content: center;
		min-width: 3.5rem;
		height: 2.4rem;
		padding: 0 0.5rem;
		background: var(--db-blue);
		color: var(--db-yellow);
		font-size: 1.1rem;
		font-weight: 700;
		border-radius: 2px;
		letter-spacing: 0.02em;
	}

	.headsign {
		margin: 0 0 0.25rem;
		font-size: 0.95rem;
		font-weight: 600;
		color: var(--db-blue);
		white-space: nowrap;
		overflow: hidden;
		text-overflow: ellipsis;
	}

	.times {
		display: flex;
		gap: 0.6rem;
		align-items: baseline;
	}
	.time {
		font-variant-numeric: tabular-nums;
		font-size: 0.85rem;
	}
	.time.scheduled {
		color: #555;
	}
	.time.estimated {
		color: #ef4444;
		font-weight: 600;
	}

	.card-status {
		display: flex;
		flex-direction: column;
		align-items: flex-end;
		gap: 0.3rem;
		padding-top: 0.1rem;
	}

	.status-badge {
		font-size: 0.7rem;
		font-weight: 600;
		text-transform: uppercase;
		letter-spacing: 0.06em;
		padding: 0.15rem 0.45rem;
		border-radius: 2px;
	}
	.status-badge.on-time {
		background: #d1fae5;
		color: #065f46;
	}
	.status-badge.minor-delay {
		background: #fef3c7;
		color: #92400e;
	}
	.status-badge.major-delay {
		background: #fee2e2;
		color: #991b1b;
	}

	.due {
		font-size: 1rem;
		font-weight: 700;
		color: var(--db-blue);
		font-variant-numeric: tabular-nums;
	}
</style>

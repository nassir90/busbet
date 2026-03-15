<script lang="ts">
	import { goto, invalidate } from '$app/navigation';
	import DepartureBoard from '$lib/components/DepartureBoard.svelte';
	import StopSearch from '$lib/components/StopSearch.svelte';
	import type { Stop } from '$lib/server/storage/types.js';
	import type { PageData } from './$types';

	const { data }: { data: PageData } = $props();

	function onStopSelected(stop: Stop) {
		goto(`/stop/${stop.stop_code}`);
	}

	function onBalanceUpdate(_balance: number) {
		// Invalidate layout data so the balance chip updates
		invalidate('app:balance');
	}
</script>

<svelte:head>
	<title>{data.stop.stop_name} ({data.stop.stop_code}) — BusBet</title>
</svelte:head>

<div class="page">
	<div class="search-row">
		<StopSearch onSelect={onStopSelected} />
	</div>
	<DepartureBoard stop={data.stop} initialDepartures={data.departures} {onBalanceUpdate} />
</div>

<style>
	.page {
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: 1.5rem;
		width: 100%;
		max-width: 680px;
	}
	.search-row {
		width: 100%;
	}
</style>

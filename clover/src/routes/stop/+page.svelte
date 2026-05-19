<script lang="ts">
	import { goto } from '$app/navigation';
	import StopSearch from '$lib/components/StopSearch.svelte';
	import type { Stop } from '$lib/server/storage/types.js';

	function onStopSelected(stop: Stop) {
		goto(`/stop/${stop.stop_code}`);
	}
</script>

<svelte:head>
	<title>Find a stop — clover</title>
</svelte:head>

<section class="search-page">
	<h1 class="title">Find a stop</h1>
	<StopSearch onSelect={onStopSelected} />
</section>

<style>
	.search-page {
		max-width: 520px;
		width: 100%;
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: 1.5rem;
	}
	.title {
		font-size: 1.4rem;
		font-weight: 700;
		color: var(--clover-dark);
		margin: 0;
	}
</style>

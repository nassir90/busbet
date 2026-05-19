<script lang="ts">
	import type { PageData } from './$types';
	import MarketCard from '$lib/components/MarketCard.svelte';
	import PlaybackControls from '$lib/components/PlaybackControls.svelte';

	const { data }: { data: PageData } = $props();
</script>

<svelte:head>
	<title>clover — Dublin Bus Prediction Markets</title>
</svelte:head>

<section class="hero">
	<div class="hero-inner">
		<h1 class="hero-title">clover</h1>
		<p class="hero-subtitle">Will your bus be on time? Place your bets.</p>
	</div>
</section>

<section class="playback">
	<PlaybackControls playback={data.playback} range={data.range} />
</section>

<section class="markets">
	<h2 class="section-title">Markets</h2>
	<div class="market-grid">
		{#each data.markets as market (market.id)}
			<MarketCard {market} />
		{/each}
	</div>
</section>

<style>
	.hero {
		width: 100%;
		display: flex;
		justify-content: center;
		padding: 2rem 1rem 1rem;
	}
	.hero-inner {
		text-align: center;
	}
	.hero-title {
		margin: 0;
		font-size: 2.8rem;
		font-weight: 800;
		color: var(--clover-dark);
		letter-spacing: -0.03em;
	}
	.hero-subtitle {
		margin: 0.3rem 0 0;
		color: #666;
		font-size: 1.05rem;
	}

	.playback {
		width: 100%;
		max-width: 600px;
		padding: 0 1rem;
		margin-bottom: 1rem;
	}

	.markets {
		width: 100%;
		max-width: 700px;
		padding: 0 1rem;
	}

	.section-title {
		font-size: 0.8rem;
		font-weight: 700;
		text-transform: uppercase;
		letter-spacing: 0.1em;
		color: var(--clover-dark);
		margin: 0 0 0.8rem;
	}

	.market-grid {
		display: flex;
		flex-direction: column;
		gap: 0.75rem;
	}
</style>

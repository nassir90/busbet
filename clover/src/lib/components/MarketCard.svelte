<script lang="ts">
	import type { Market } from '$lib/server/storage/types.js';

	interface Props {
		market: Market;
	}
	const { market }: Props = $props();
</script>

<a href="/market/{market.id}" class="card">
	<div class="card-route">
		<span class="route-badge">{market.route_short_name}</span>
	</div>
	<div class="card-body">
		<p class="card-title">
			{market.stop_name}
			<span class="direction">&#8594; {market.direction}</span>
		</p>
		<p class="card-desc">{market.description}</p>
	</div>
	<div class="card-arrow">&#8250;</div>
</a>

<style>
	.card {
		display: grid;
		grid-template-columns: 4rem 1fr 2rem;
		align-items: center;
		gap: 1rem;
		padding: 1rem 1.2rem;
		background: #fff;
		border: 1px solid #d1e7dd;
		border-left: 4px solid var(--clover);
		border-radius: 4px;
		text-decoration: none;
		color: inherit;
		transition: box-shadow 0.15s, border-color 0.15s;
	}
	.card:hover {
		box-shadow: 0 4px 12px rgba(22, 163, 74, 0.15);
		border-color: var(--clover);
	}

	.route-badge {
		display: inline-flex;
		align-items: center;
		justify-content: center;
		min-width: 3.2rem;
		height: 2.2rem;
		padding: 0 0.4rem;
		background: var(--clover);
		color: #fff;
		font-size: 1rem;
		font-weight: 700;
		border-radius: 3px;
		letter-spacing: 0.02em;
	}

	.card-body {
		min-width: 0;
	}
	.card-title {
		margin: 0;
		font-size: 0.95rem;
		font-weight: 600;
		color: var(--clover-dark);
	}
	.direction {
		color: #666;
		font-weight: 400;
	}
	.card-desc {
		margin: 0.2rem 0 0;
		font-size: 0.8rem;
		color: #888;
		white-space: nowrap;
		overflow: hidden;
		text-overflow: ellipsis;
	}

	.card-arrow {
		font-size: 1.5rem;
		color: #ccc;
		font-weight: 300;
	}
</style>

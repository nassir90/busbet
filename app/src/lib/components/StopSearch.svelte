<script lang="ts">
	import type { Stop } from '$lib/server/storage/types.js';

	interface Props {
		onSelect: (stop: Stop) => void;
	}
	const { onSelect }: Props = $props();

	let query = $state('');
	let results: Stop[] = $state([]);
	let loading = $state(false);
	let debounceTimer: ReturnType<typeof setTimeout> | null = null;

	async function search(q: string) {
		if (q.length < 2) {
			results = [];
			return;
		}
		loading = true;
		try {
			const res = await fetch(`/api/stops?q=${encodeURIComponent(q)}`);
			if (res.ok) results = await res.json();
		} finally {
			loading = false;
		}
	}

	function onInput() {
		if (debounceTimer) clearTimeout(debounceTimer);
		debounceTimer = setTimeout(() => search(query), 200);
	}

	function select(stop: Stop) {
		query = '';
		results = [];
		onSelect(stop);
	}
</script>

<div class="search-wrapper">
	<label class="search-label" for="stop-search">Find a stop</label>
	<div class="search-box">
		<input
			id="stop-search"
			type="search"
			placeholder="Stop name or number (e.g. 7634, O'Connell St)"
			bind:value={query}
			oninput={onInput}
			autocomplete="off"
			spellcheck="false"
		/>
		{#if loading}
			<span class="spinner" aria-label="Searching…"></span>
		{/if}
	</div>
	{#if results.length > 0}
		<ul class="results" role="listbox">
			{#each results as stop (stop.stop_id)}
				<li role="option" aria-selected="false">
					<button onclick={() => select(stop)}>
						<span class="stop-code">{stop.stop_code}</span>
						<span class="stop-name">{stop.stop_name}</span>
					</button>
				</li>
			{/each}
		</ul>
	{/if}
</div>

<style>
	.search-wrapper {
		position: relative;
		max-width: 520px;
		width: 100%;
	}

	.search-label {
		display: block;
		font-size: 0.8rem;
		font-weight: 700;
		text-transform: uppercase;
		letter-spacing: 0.08em;
		color: var(--db-blue);
		margin-bottom: 0.4rem;
	}

	.search-box {
		position: relative;
		display: flex;
		align-items: center;
	}

	input[type='search'] {
		width: 100%;
		padding: 0.7rem 1rem;
		font-size: 1rem;
		border: 2px solid var(--db-blue);
		border-radius: 2px;
		background: #fff;
		color: #111;
		outline: none;
		font-family: inherit;
	}
	input[type='search']:focus {
		border-color: var(--db-yellow);
		box-shadow: 0 0 0 3px rgba(255, 210, 0, 0.35);
	}

	.spinner {
		position: absolute;
		right: 0.8rem;
		width: 1.1rem;
		height: 1.1rem;
		border: 2px solid var(--db-blue);
		border-top-color: transparent;
		border-radius: 50%;
		animation: spin 0.6s linear infinite;
	}
	@keyframes spin {
		to { transform: rotate(360deg); }
	}

	.results {
		position: absolute;
		top: calc(100% + 2px);
		left: 0;
		right: 0;
		background: #fff;
		border: 2px solid var(--db-blue);
		border-top: none;
		border-radius: 0 0 2px 2px;
		list-style: none;
		margin: 0;
		padding: 0;
		z-index: 100;
		max-height: 260px;
		overflow-y: auto;
	}

	.results li button {
		display: flex;
		align-items: center;
		gap: 0.7rem;
		width: 100%;
		padding: 0.6rem 1rem;
		background: none;
		border: none;
		cursor: pointer;
		text-align: left;
		font-family: inherit;
		font-size: 0.9rem;
		color: #111;
	}
	.results li button:hover {
		background: var(--db-yellow);
		color: var(--db-blue);
	}

	.stop-code {
		font-weight: 700;
		color: var(--db-blue);
		min-width: 3rem;
		font-variant-numeric: tabular-nums;
	}
	.results li button:hover .stop-code {
		color: var(--db-blue);
	}

	.stop-name {
		flex: 1;
	}
</style>

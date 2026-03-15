<script lang="ts">
	import type { Wager } from '$lib/server/storage/types.js';

	interface Props {
		tripId: string;
		stopId: string;
		scheduledDeparture: string;
		onWagerPlaced: (balance: number) => void;
	}
	const { tripId, stopId, scheduledDeparture, onWagerPlaced }: Props = $props();

	let open = $state(false);
	let wagerType = $state<'drift' | 'cancellation'>('drift');
	let driftMinutes = $state(5);
	let stake = $state(5);
	let submitting = $state(false);
	let result = $state<{ wager: Wager; balance: number } | null>(null);
	let errorMsg = $state<string | null>(null);

	async function submit(e: Event) {
		e.preventDefault();
		submitting = true;
		errorMsg = null;
		try {
			const res = await fetch('/api/wagers', {
				method: 'POST',
				headers: { 'Content-Type': 'application/json' },
				body: JSON.stringify({
					tripId,
					stopId,
					scheduledDeparture,
					wagerType,
					driftMinutes: wagerType === 'drift' ? driftMinutes : null,
					stake
				})
			});
			if (res.ok) {
				result = await res.json();
				onWagerPlaced(result!.balance);
				setTimeout(() => { open = false; result = null; }, 3000);
			} else {
				const data = await res.json().catch(() => ({}));
				errorMsg = data.message ?? `Error ${res.status}`;
			}
		} catch {
			errorMsg = 'Network error';
		} finally {
			submitting = false;
		}
	}

	function toggle() {
		open = !open;
		if (!open) { result = null; errorMsg = null; }
	}
</script>

<div class="wager-wrap">
	<button class="wager-toggle" onclick={toggle} aria-expanded={open}>
		{open ? 'Cancel' : 'Bet'}
	</button>

	{#if open}
		{#if result}
			<div class="wager-success">
				Wager placed! €{result.wager.payout?.toFixed(2) ?? result.wager.stake.toFixed(2)} —
				{result.wager.wager_type === 'drift'
					? `${result.wager.drift_minutes} min drift`
					: 'cancellation'}
				· Balance: €{result.balance.toFixed(2)}
			</div>
		{:else}
			<form class="wager-form" onsubmit={submit}>
				<div class="wager-type">
					<label class="radio-label" class:active={wagerType === 'drift'}>
						<input type="radio" name="type" value="drift" bind:group={wagerType} />
						Drift
					</label>
					<label class="radio-label" class:active={wagerType === 'cancellation'}>
						<input type="radio" name="type" value="cancellation" bind:group={wagerType} />
						Cancel
					</label>
				</div>

				{#if wagerType === 'drift'}
					<div class="field">
						<label for="drift-{tripId}">Drift (min late)</label>
						<input
							id="drift-{tripId}"
							type="number"
							min="-5"
							max="60"
							bind:value={driftMinutes}
							required
						/>
					</div>
				{/if}

				<div class="field">
					<label for="stake-{tripId}">Stake (€)</label>
					<input
						id="stake-{tripId}"
						type="number"
						min="1"
						max="1000"
						step="1"
						bind:value={stake}
						required
					/>
				</div>

				{#if errorMsg}
					<p class="wager-error">{errorMsg}</p>
				{/if}

				<button class="wager-submit" type="submit" disabled={submitting}>
					{submitting ? 'Placing…' : 'Place Bet'}
				</button>
			</form>
		{/if}
	{/if}
</div>

<style>
	.wager-wrap {
		border-top: 1px solid #e5e9f2;
		padding-top: 0.5rem;
		margin-top: 0.5rem;
	}

	.wager-toggle {
		font-size: 0.72rem;
		font-weight: 700;
		letter-spacing: 0.05em;
		text-transform: uppercase;
		background: var(--db-yellow);
		color: var(--db-blue);
		border: none;
		border-radius: 2px;
		padding: 0.2rem 0.6rem;
		cursor: pointer;
		transition: opacity 0.15s;
	}
	.wager-toggle:hover { opacity: 0.85; }

	.wager-form {
		display: flex;
		flex-wrap: wrap;
		gap: 0.5rem;
		align-items: flex-end;
		margin-top: 0.5rem;
	}

	.wager-type {
		display: flex;
		gap: 0.3rem;
	}

	.radio-label {
		display: flex;
		align-items: center;
		gap: 0.25rem;
		font-size: 0.8rem;
		font-weight: 600;
		padding: 0.2rem 0.5rem;
		border: 1px solid #c5cfdf;
		border-radius: 2px;
		cursor: pointer;
		background: #f5f7fb;
		transition: background 0.1s;
	}
	.radio-label.active {
		background: var(--db-blue);
		color: #fff;
		border-color: var(--db-blue);
	}
	.radio-label input { display: none; }

	.field {
		display: flex;
		flex-direction: column;
		gap: 0.15rem;
	}
	.field label {
		font-size: 0.72rem;
		color: #666;
		font-weight: 600;
	}
	.field input {
		width: 4.5rem;
		padding: 0.25rem 0.4rem;
		border: 1px solid #c5cfdf;
		border-radius: 2px;
		font-size: 0.85rem;
		font-variant-numeric: tabular-nums;
	}

	.wager-submit {
		background: var(--db-blue);
		color: #fff;
		border: none;
		border-radius: 2px;
		padding: 0.3rem 0.8rem;
		font-size: 0.8rem;
		font-weight: 700;
		cursor: pointer;
		transition: opacity 0.15s;
		align-self: flex-end;
	}
	.wager-submit:hover:not(:disabled) { opacity: 0.85; }
	.wager-submit:disabled { opacity: 0.5; cursor: default; }

	.wager-error {
		width: 100%;
		margin: 0;
		font-size: 0.78rem;
		color: #dc2626;
	}

	.wager-success {
		margin-top: 0.4rem;
		font-size: 0.8rem;
		color: #065f46;
		background: #d1fae5;
		padding: 0.3rem 0.6rem;
		border-radius: 2px;
	}
</style>

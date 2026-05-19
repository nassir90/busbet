<script lang="ts">
	interface PlaybackState {
		virtualTime: number;
		speed: number;
		paused: boolean;
	}
	interface PlaybackRange {
		earliest: number;
		latest: number;
	}

	interface Props {
		playback: PlaybackState | null;
		range: PlaybackRange | null;
	}
	const { playback, range }: Props = $props();

	let speed = $state(playback?.speed ?? 0);
	let updating = $state(false);

	const timeLabel = $derived(() => {
		if (!playback) return 'Playback offline';
		const d = new Date(playback.virtualTime * 1000);
		return d.toLocaleString('en-IE', {
			weekday: 'short',
			hour: '2-digit',
			minute: '2-digit',
			second: '2-digit',
			hour12: false
		});
	});

	const rangeLabel = $derived(() => {
		if (!range) return '';
		const e = new Date(range.earliest * 1000);
		const l = new Date(range.latest * 1000);
		return `${e.toLocaleDateString('en-IE', { month: 'short', day: 'numeric' })} - ${l.toLocaleDateString('en-IE', { month: 'short', day: 'numeric' })}`;
	});

	async function setPlayback(opts: { time?: number; speed?: number }) {
		updating = true;
		try {
			await fetch('/api/playback', {
				method: 'POST',
				headers: { 'Content-Type': 'application/json' },
				body: JSON.stringify(opts)
			});
			if (opts.speed !== undefined) speed = opts.speed;
		} finally {
			updating = false;
		}
	}

	function play() { setPlayback({ speed: 1 }); }
	function pause() { setPlayback({ speed: 0 }); }
	function fast() { setPlayback({ speed: 60 }); }
</script>

<div class="controls">
	<div class="time-display">
		<span class="clock">{timeLabel()}</span>
		{#if range}
			<span class="range">{rangeLabel()}</span>
		{/if}
	</div>
	<div class="buttons">
		{#if !playback}
			<span class="offline">Playback server offline</span>
		{:else}
			<button class="ctrl-btn" onclick={pause} disabled={updating || speed === 0} title="Pause">
				&#9646;&#9646;
			</button>
			<button class="ctrl-btn" onclick={play} disabled={updating || speed === 1} title="Play 1x">
				&#9654;
			</button>
			<button class="ctrl-btn" onclick={fast} disabled={updating || speed === 60} title="60x speed">
				&#9654;&#9654;
			</button>
		{/if}
	</div>
</div>

<style>
	.controls {
		display: flex;
		align-items: center;
		justify-content: space-between;
		background: #fff;
		border: 1px solid #d1e7dd;
		border-radius: 4px;
		padding: 0.6rem 1rem;
		font-size: 0.85rem;
	}

	.time-display {
		display: flex;
		flex-direction: column;
		gap: 0.1rem;
	}

	.clock {
		font-weight: 600;
		font-variant-numeric: tabular-nums;
		color: var(--clover-dark);
	}

	.range {
		font-size: 0.7rem;
		color: #999;
	}

	.buttons {
		display: flex;
		gap: 0.4rem;
	}

	.ctrl-btn {
		background: var(--clover);
		color: #fff;
		border: none;
		border-radius: 3px;
		padding: 0.3rem 0.6rem;
		cursor: pointer;
		font-size: 0.8rem;
		transition: background 0.15s;
	}
	.ctrl-btn:hover:not(:disabled) {
		background: var(--clover-dark);
	}
	.ctrl-btn:disabled {
		opacity: 0.4;
		cursor: default;
	}

	.offline {
		color: #999;
		font-size: 0.8rem;
	}
</style>

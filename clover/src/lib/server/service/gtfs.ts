/** Playback server control — only used in clover's backtesting/demo mode. */
const PLAYBACK_URL = process.env.PLAYBACK_URL ?? 'http://localhost:3456';

export async function getPlaybackTime(): Promise<{ virtualTime: number; speed: number; paused: boolean } | null> {
	try {
		const res = await fetch(`${PLAYBACK_URL}/api/control/time`);
		if (!res.ok) return null;
		return await res.json();
	} catch {
		return null;
	}
}

export async function getPlaybackRange(): Promise<{ earliest: number; latest: number } | null> {
	try {
		const res = await fetch(`${PLAYBACK_URL}/api/control/range`);
		if (!res.ok) return null;
		return await res.json();
	} catch {
		return null;
	}
}

export async function setPlaybackTime(opts: { time?: number; speed?: number }): Promise<void> {
	try {
		await fetch(`${PLAYBACK_URL}/api/control/time`, {
			method: 'POST',
			headers: { 'Content-Type': 'application/json' },
			body: JSON.stringify(opts)
		});
	} catch {
		// playback server not running — silently ignore
	}
}

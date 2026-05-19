<script lang="ts">
	import { onMount } from 'svelte';
	import { invalidate } from '$app/navigation';
	import type { LayoutData } from './$types';

	let { data, children }: { data: LayoutData; children: any } = $props();

	onMount(() => {
		const {
			data: { subscription }
		} = data.supabase.auth.onAuthStateChange((_event: string, _session: any) => {
			invalidate('supabase:auth');
		});

		return () => subscription.unsubscribe();
	});
</script>

<nav>
	<a href="/">Home</a>
	{#if data.session}
		<a href="/dashboard">Dashboard</a>
		<a href="/account">Account</a>
		<form method="POST" action="/logout" style="display:inline">
			<button type="submit">Logout</button>
		</form>
	{:else}
		<a href="/login">Login</a>
		<a href="/signup">Sign Up</a>
	{/if}
</nav>

<main>
	{@render children()}
</main>

<style>
	:global(body) {
		font-family: system-ui, -apple-system, 'Segoe UI', sans-serif;
		margin: 0;
		padding: 0;
		background: #fafafa;
		color: #111;
	}

	nav {
		display: flex;
		align-items: center;
		gap: 1rem;
		padding: 0.75rem 1.5rem;
		background: #111;
	}

	nav a {
		color: #fff;
		text-decoration: none;
		font-size: 0.9rem;
	}

	nav a:hover {
		text-decoration: underline;
	}

	nav button {
		background: none;
		border: 1px solid #555;
		color: #fff;
		padding: 0.3rem 0.7rem;
		border-radius: 4px;
		cursor: pointer;
		font-family: inherit;
		font-size: 0.85rem;
	}

	nav button:hover {
		border-color: #aaa;
	}

	main {
		max-width: 480px;
		margin: 2rem auto;
		padding: 0 1rem;
	}
</style>

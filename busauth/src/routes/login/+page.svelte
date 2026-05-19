<script lang="ts">
	import type { LayoutData } from '../$types';
	let { data }: { data: LayoutData } = $props();

	let email = $state('');
	let password = $state('');
	let error = $state('');
	let message = $state('');
	let loading = $state(false);
	let mode = $state<'password' | 'magic'>('password');

	async function handlePasswordLogin(e: Event) {
		e.preventDefault();
		loading = true;
		error = '';

		const { error: err } = await data.supabase.auth.signInWithPassword({ email, password });

		if (err) {
			error = err.message;
			loading = false;
		} else {
			window.location.href = '/dashboard';
		}
	}

	async function handleMagicLink(e: Event) {
		e.preventDefault();
		loading = true;
		error = '';
		message = '';

		const { error: err } = await data.supabase.auth.signInWithOtp({
			email,
			options: { emailRedirectTo: `${window.location.origin}/auth/callback` }
		});

		if (err) {
			error = err.message;
		} else {
			message = 'Check your email for a magic link!';
		}
		loading = false;
	}
</script>

<h1>Login</h1>

<div class="tabs">
	<button class:active={mode === 'password'} onclick={() => (mode = 'password')}>Password</button>
	<button class:active={mode === 'magic'} onclick={() => (mode = 'magic')}>Magic Link</button>
</div>

{#if mode === 'password'}
	<form onsubmit={handlePasswordLogin}>
		<label>
			Email
			<input type="email" bind:value={email} required />
		</label>
		<label>
			Password
			<input type="password" bind:value={password} required />
		</label>
		{#if error}<p class="error">{error}</p>{/if}
		<button type="submit" disabled={loading}>
			{loading ? 'Signing in...' : 'Sign in'}
		</button>
	</form>
{:else}
	<form onsubmit={handleMagicLink}>
		<label>
			Email
			<input type="email" bind:value={email} required />
		</label>
		{#if error}<p class="error">{error}</p>{/if}
		{#if message}<p class="success">{message}</p>{/if}
		<button type="submit" disabled={loading}>
			{loading ? 'Sending...' : 'Send magic link'}
		</button>
	</form>
{/if}

<p>No account? <a href="/signup">Sign up</a></p>

<style>
	.tabs {
		display: flex;
		gap: 0;
		margin-bottom: 1rem;
		border-bottom: 2px solid #ddd;
	}
	.tabs button {
		flex: 1;
		padding: 0.5rem;
		background: none;
		border: none;
		border-bottom: 2px solid transparent;
		margin-bottom: -2px;
		cursor: pointer;
		font-family: inherit;
		font-size: 0.9rem;
		color: #666;
	}
	.tabs button.active {
		color: #111;
		border-bottom-color: #111;
		font-weight: 600;
	}

	form {
		display: flex;
		flex-direction: column;
		gap: 0.75rem;
	}

	label {
		display: flex;
		flex-direction: column;
		gap: 0.25rem;
		font-size: 0.9rem;
	}

	input {
		padding: 0.5rem;
		border: 1px solid #ccc;
		border-radius: 4px;
		font-family: inherit;
		font-size: 0.95rem;
	}

	form button[type='submit'] {
		padding: 0.6rem;
		background: #111;
		color: #fff;
		border: none;
		border-radius: 4px;
		cursor: pointer;
		font-family: inherit;
		font-size: 0.95rem;
	}

	form button[type='submit']:disabled {
		opacity: 0.6;
		cursor: not-allowed;
	}

	.error {
		color: #c00;
		margin: 0;
		font-size: 0.9rem;
	}

	.success {
		color: #060;
		margin: 0;
		font-size: 0.9rem;
	}
</style>

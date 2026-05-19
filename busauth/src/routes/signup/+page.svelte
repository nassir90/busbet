<script lang="ts">
	import type { LayoutData } from '../$types';
	let { data }: { data: LayoutData } = $props();

	let email = $state('');
	let password = $state('');
	let error = $state('');
	let message = $state('');
	let loading = $state(false);

	async function handleSignup(e: Event) {
		e.preventDefault();
		loading = true;
		error = '';
		message = '';

		const { error: err } = await data.supabase.auth.signUp({ email, password });

		if (err) {
			error = err.message;
		} else {
			message = 'Check your email for a confirmation link.';
		}
		loading = false;
	}
</script>

<h1>Sign Up</h1>

<form onsubmit={handleSignup}>
	<label>
		Email
		<input type="email" bind:value={email} required />
	</label>
	<label>
		Password
		<input type="password" bind:value={password} required minlength="6" />
	</label>
	{#if error}
		<p class="error">{error}</p>
	{/if}
	{#if message}
		<p class="success">{message}</p>
	{/if}
	<button type="submit" disabled={loading}>
		{loading ? 'Signing up...' : 'Sign up'}
	</button>
</form>

<p>Already have an account? <a href="/login">Login</a></p>

<style>
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

	button {
		padding: 0.6rem;
		background: #111;
		color: #fff;
		border: none;
		border-radius: 4px;
		cursor: pointer;
		font-family: inherit;
		font-size: 0.95rem;
	}

	button:disabled {
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

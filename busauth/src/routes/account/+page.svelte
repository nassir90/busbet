<script lang="ts">
	import type { PageData } from './$types';
	import Avatar from '$lib/components/Avatar.svelte';

	let { data }: { data: PageData } = $props();

	let loading = $state(false);
	let username = $state(data.profile?.username ?? '');
	let fullName = $state(data.profile?.full_name ?? '');
	let website = $state(data.profile?.website ?? '');
	let avatarUrl = $state(data.profile?.avatar_url ?? '');
	let message = $state('');
	let error = $state('');

	async function updateProfile() {
		loading = true;
		error = '';
		message = '';

		const { error: err } = await data.supabase
			.from('profiles')
			.upsert({
				id: data.session!.user.id,
				username,
				full_name: fullName,
				website,
				avatar_url: avatarUrl,
				updated_at: new Date().toISOString()
			});

		if (err) {
			error = err.message;
		} else {
			message = 'Profile updated!';
		}
		loading = false;
	}

	function onAvatarUpload(path: string) {
		avatarUrl = path;
		updateProfile();
	}
</script>

<h1>Account</h1>

<div class="profile-form">
	<Avatar supabase={data.supabase} size={120} url={avatarUrl} onUpload={onAvatarUpload} />

	<form onsubmit={(e) => { e.preventDefault(); updateProfile(); }}>
		<label>
			Email
			<input type="text" value={data.session?.user.email ?? ''} disabled />
		</label>
		<label>
			Username
			<input type="text" bind:value={username} minlength="3" />
		</label>
		<label>
			Full Name
			<input type="text" bind:value={fullName} />
		</label>
		<label>
			Website
			<input type="url" bind:value={website} placeholder="https://" />
		</label>

		{#if error}<p class="error">{error}</p>{/if}
		{#if message}<p class="success">{message}</p>{/if}

		<button type="submit" disabled={loading}>
			{loading ? 'Saving...' : 'Update profile'}
		</button>
	</form>
</div>

<style>
	.profile-form {
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: 1.5rem;
	}

	form {
		width: 100%;
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

	input:disabled {
		background: #f5f5f5;
		color: #888;
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

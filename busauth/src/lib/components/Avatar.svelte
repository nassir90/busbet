<script lang="ts">
	import type { SupabaseClient } from '@supabase/supabase-js';

	interface Props {
		supabase: SupabaseClient;
		size?: number;
		url: string | null;
		onUpload: (url: string) => void;
	}
	const { supabase, size = 150, url, onUpload }: Props = $props();

	let uploading = $state(false);
	let avatarUrl = $state<string | null>(null);

	$effect(() => {
		if (url) downloadImage(url);
	});

	async function downloadImage(path: string) {
		try {
			const { data, error } = await supabase.storage.from('avatars').download(path);
			if (error) throw error;
			avatarUrl = URL.createObjectURL(data);
		} catch (err) {
			console.error('Error downloading avatar:', err);
		}
	}

	async function uploadAvatar(e: Event) {
		const target = e.target as HTMLInputElement;
		const files = target.files;
		if (!files || files.length === 0) return;

		uploading = true;
		const file = files[0];
		const fileExt = file.name.split('.').pop();
		const filePath = `${Math.random().toString(36).slice(2)}.${fileExt}`;

		const { error } = await supabase.storage.from('avatars').upload(filePath, file);

		if (error) {
			alert(error.message);
		} else {
			onUpload(filePath);
		}
		uploading = false;
	}
</script>

<div class="avatar" style="width: {size}px">
	{#if avatarUrl}
		<img src={avatarUrl} alt="Avatar" width={size} height={size} />
	{:else}
		<div class="placeholder" style="width: {size}px; height: {size}px">No image</div>
	{/if}

	<label class="upload-btn">
		{uploading ? 'Uploading...' : 'Change avatar'}
		<input type="file" accept="image/*" onchange={uploadAvatar} disabled={uploading} />
	</label>
</div>

<style>
	.avatar {
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: 0.5rem;
	}

	img {
		border-radius: 50%;
		object-fit: cover;
	}

	.placeholder {
		border-radius: 50%;
		background: #e0e0e0;
		display: flex;
		align-items: center;
		justify-content: center;
		color: #999;
		font-size: 0.8rem;
	}

	.upload-btn {
		font-size: 0.8rem;
		color: #111;
		cursor: pointer;
		text-decoration: underline;
	}

	.upload-btn input {
		display: none;
	}
</style>

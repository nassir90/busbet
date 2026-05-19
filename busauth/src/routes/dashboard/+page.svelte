<script lang="ts">
	import type { PageData } from './$types';
	let { data }: { data: PageData } = $props();
</script>

<h1>Dashboard</h1>

<div class="card">
	<p><strong>Email:</strong> {data.user?.email}</p>
	<p><strong>User ID:</strong> <code>{data.user?.id}</code></p>
	<p><strong>Created:</strong> {data.user?.created_at ? new Date(data.user.created_at).toLocaleString() : '—'}</p>
</div>

<style>
	.card {
		background: #fff;
		border: 1px solid #ddd;
		border-radius: 6px;
		padding: 1rem 1.25rem;
	}

	.card p {
		margin: 0.4rem 0;
		font-size: 0.9rem;
	}

	code {
		font-size: 0.8rem;
		background: #f0f0f0;
		padding: 0.1rem 0.3rem;
		border-radius: 3px;
	}
</style>

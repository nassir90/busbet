<script lang="ts">
	import { page } from '$app/stores';
</script>

<svelte:head>
	<title>Error — BusBet</title>
</svelte:head>

<div class="error-page">
	<div class="error-card">
		<span class="error-code">{$page.status}</span>
		<p class="error-msg">{$page.error?.message ?? 'An unexpected error occurred.'}</p>
		<a href="/" class="home-link">← Back to search</a>
	</div>
</div>

<style>
	.error-page {
		display: flex;
		justify-content: center;
		padding: 3rem 1rem;
	}
	.error-card {
		background: #fff;
		border: 1px solid #d1daea;
		border-left: 4px solid #ef4444;
		border-radius: 2px;
		padding: 2rem 2.5rem;
		text-align: center;
		max-width: 420px;
	}
	.error-code {
		display: block;
		font-size: 3rem;
		font-weight: 800;
		color: #ef4444;
		line-height: 1;
		margin-bottom: 0.5rem;
	}
	.error-msg {
		color: #444;
		margin: 0 0 1.5rem;
	}
	.home-link {
		font-weight: 600;
		color: var(--db-blue);
	}
</style>

<script lang="ts">
	import type { LayoutData } from './$types';
	let { data }: { data: LayoutData } = $props();
</script>

<h1>busauth</h1>
<p>Supabase Auth demo.</p>

{#if data.session}
	<p>Signed in as <strong>{data.session.user.email}</strong>.</p>
	<p><a href="/dashboard">Go to dashboard</a></p>
{:else}
	<p><a href="/login">Login</a> or <a href="/signup">sign up</a> to continue.</p>
{/if}

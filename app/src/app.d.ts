// See https://svelte.dev/docs/kit/types#app.d.ts

declare global {
	namespace App {
		interface Error {
			message: string;
			issues?: unknown[];
		}
		interface Locals {
			userId: string;
		}
		// interface PageData {}
		// interface PageState {}
		// interface Platform {}
	}

	namespace NodeJS {
		interface ProcessEnv {
			NTA_API_KEY: string;
			STORAGE_BACKEND?: 'sqlite' | 'supabase';
			DATABASE_URL?: string;
			SUPABASE_URL?: string;
			SUPABASE_ANON_KEY?: string;
		}
	}
}

export {};

declare global {
	namespace App {
		interface Error {
			message: string;
		}
		// interface Locals {}
		// interface PageData {}
		// interface PageState {}
		// interface Platform {}
	}

	namespace NodeJS {
		interface ProcessEnv {
			DATABASE_URL?: string;
			PLAYBACK_URL?: string;
		}
	}
}

export {};

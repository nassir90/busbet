import { STORAGE_BACKEND, DATABASE_URL } from '$env/static/private';
import { env as dynamicEnv } from '$env/dynamic/private';
import type { StorageBackend } from './interface.js';
import { createSqliteBackend } from './sqlite.js';
import { createSupabaseBackend } from './supabase.js';
import { createMockBackend } from './mock.js';

let _backend: StorageBackend | null = null;
let _init: Promise<StorageBackend> | null = null;

export function getStorage(): Promise<StorageBackend> {
	if (_backend) return Promise.resolve(_backend);
	if (_init) return _init;

	_init = (async () => {
		const backendType = STORAGE_BACKEND ?? 'sqlite';

		if (backendType === 'mock') {
			_backend = createMockBackend();
		} else if (backendType === 'supabase') {
			const supabaseUrl = dynamicEnv.SUPABASE_URL;
			const supabaseKey = dynamicEnv.SUPABASE_ANON_KEY;
			if (!supabaseUrl || !supabaseKey) {
				throw new Error('SUPABASE_URL and SUPABASE_ANON_KEY must be set for supabase backend');
			}
			_backend = createSupabaseBackend(supabaseUrl, supabaseKey);
		} else {
			const dbPath = DATABASE_URL ?? './data/busbet.db';
			_backend = await createSqliteBackend(dbPath);
		}

		return _backend;
	})();

	return _init;
}

export type { StorageBackend } from './interface.js';
export type { Stop, Route, Trip, StopTime, Departure } from './types.js';

import type { StorageBackend } from './interface.js';
import { createSqliteBackend } from './sqlite.js';

let _backend: StorageBackend | null = null;
let _init: Promise<StorageBackend> | null = null;

export function getStorage(): Promise<StorageBackend> {
	if (_backend) return Promise.resolve(_backend);
	if (_init) return _init;

	_init = (async () => {
		// Read-only: use the gtfsr-collector's static GTFS database
		const dbPath = process.env.DATABASE_URL ?? '../gtfsr-collector/data/busbet.db';
		_backend = await createSqliteBackend(dbPath);
		return _backend;
	})();

	return _init;
}

export type { StorageBackend } from './interface.js';
export type { Stop, Route, Trip, StopTime, Departure } from './types.js';

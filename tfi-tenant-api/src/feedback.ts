import { mkdirSync, readdirSync, renameSync, statSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { randomBytes } from 'node:crypto';

/**
 * Feedback from the app, kept as loose files: `<id>.json`, plus `<id>.jpg|png|webp` when a
 * screenshot came with it.
 *
 * Files rather than a table in reports.db because the bulk is images, read by a person on the box
 * (or copied off it), never queried. There is deliberately no endpoint that lists or serves them:
 * a screenshot can show someone's favourite stops, and this API has no authentication.
 *
 * The folder is capped at [maxBytes] in total. A submission that would take it past the cap is
 * refused with 507 rather than evicting older feedback: nothing here has been read yet, so the
 * oldest is no less valuable than the newest. Raising the cap is a config change.
 */

/** Ceiling on one request body: a phone screenshot as base64 plus the text, with room to spare. */
export const FEEDBACK_MAX_BODY_BYTES = 8 * 1024 * 1024;
/** Ceiling on one decoded screenshot. */
const MAX_IMAGE_BYTES = 6 * 1024 * 1024;
const MAX_DESCRIPTION = 10_000;
const MAX_SCREEN = 200;
const MAX_CONTEXT = 64 * 1024;
const MAX_BOXES = 50;
const MAX_LABEL = 60;
const MAX_VERSION = 40;

export type FeedbackResult = { ok: true; id: string } | { error: string; status: number };

interface Box {
	index: number;
	left: number;
	top: number;
	right: number;
	bottom: number;
	label?: string;
}

function imageExtension(bytes: Buffer): 'png' | 'jpg' | 'webp' | null {
	if (bytes.length >= 8 && bytes.readUInt32BE(0) === 0x89504e47) return 'png';
	if (bytes.length >= 3 && bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff) return 'jpg';
	if (bytes.length >= 12 && bytes.toString('ascii', 0, 4) === 'RIFF' && bytes.toString('ascii', 8, 12) === 'WEBP') return 'webp';
	return null;
}

function isFraction(n: unknown): n is number {
	return typeof n === 'number' && Number.isFinite(n) && n >= 0 && n <= 1;
}

function parseBoxes(raw: unknown): Box[] | null {
	if (raw == null) return [];
	if (!Array.isArray(raw) || raw.length > MAX_BOXES) return null;
	const boxes: Box[] = [];
	for (const b of raw) {
		if (typeof b !== 'object' || b === null) return null;
		const { index, left, top, right, bottom, label } = b as Record<string, unknown>;
		if (!Number.isInteger(index) || !isFraction(left) || !isFraction(top) || !isFraction(right) || !isFraction(bottom)) {
			return null;
		}
		if (label != null && (typeof label !== 'string' || label.length > MAX_LABEL)) return null;
		boxes.push({ index: index as number, left, top, right, bottom, ...(label ? { label } : {}) });
	}
	return boxes;
}

function optionalString(v: unknown, max: number): string | null {
	if (v == null) return '';
	if (typeof v !== 'string' || v.length > max) return null;
	return v;
}

/** `20261009T171530Z-a1b2c3`: sorts by arrival, and can't collide within a second. */
function newId(now: number): string {
	const stamp = new Date(now).toISOString().replace(/[-:]/g, '').replace(/\.\d+Z$/, 'Z');
	return `${stamp}-${randomBytes(3).toString('hex')}`;
}

function folderSize(dir: string): number {
	let total = 0;
	for (const name of readdirSync(dir)) {
		const s = statSync(join(dir, name));
		if (s.isFile()) total += s.size;
	}
	return total;
}

/** Written beside its final name, then renamed: a crash mid-write never leaves a torn file. */
function writeAtomic(path: string, data: Buffer | string) {
	const tmp = `${path}.tmp`;
	writeFileSync(tmp, data);
	renameSync(tmp, path);
}

export function createFeedbackStore(dir: string, maxBytes: number) {
	mkdirSync(dir, { recursive: true });
	// Measured once at startup and kept up to date by save(): this process is the folder's only
	// writer, and re-walking it per request would grow with the folder.
	let used = folderSize(dir);

	return {
		get usedBytes() { return used; },
		maxBytes,

		save(body: unknown, now = Date.now()): FeedbackResult {
			if (typeof body !== 'object' || body === null) return { status: 400, error: 'expected a JSON object' };
			const b = body as Record<string, unknown>;

			const description = optionalString(b.description, MAX_DESCRIPTION);
			const screen = optionalString(b.screen, MAX_SCREEN);
			const context = optionalString(b.context, MAX_CONTEXT);
			const appVersion = optionalString(b.app_version, MAX_VERSION);
			if (description == null) return { status: 400, error: `description must be text of at most ${MAX_DESCRIPTION} characters` };
			if (screen == null) return { status: 400, error: `screen must be text of at most ${MAX_SCREEN} characters` };
			if (context == null) return { status: 400, error: `context must be text of at most ${MAX_CONTEXT} characters` };
			if (appVersion == null) return { status: 400, error: `app_version must be text of at most ${MAX_VERSION} characters` };
			const boxes = parseBoxes(b.boxes);
			if (boxes == null) return { status: 400, error: `boxes must be at most ${MAX_BOXES} boxes with fractional edges` };

			let image: Buffer | null = null;
			let ext: string | null = null;
			if (b.screenshot_base64 != null) {
				if (typeof b.screenshot_base64 !== 'string') return { status: 400, error: 'screenshot_base64 must be a string' };
				image = Buffer.from(b.screenshot_base64, 'base64');
				if (image.length === 0) return { status: 400, error: 'screenshot_base64 is not valid base64' };
				if (image.length > MAX_IMAGE_BYTES) return { status: 413, error: 'screenshot is too large' };
				ext = imageExtension(image);
				if (!ext) return { status: 415, error: 'screenshot must be a PNG, JPEG or WebP image' };
			}
			if (!description.trim() && !image) return { status: 400, error: 'feedback needs a description or a screenshot' };

			const id = newId(now);
			// The app's context is JSON text; kept as an object when it parses, so the file reads
			// as one document rather than JSON wrapped in a string.
			let parsedContext: unknown = context;
			try { if (context) parsedContext = JSON.parse(context); } catch { /* keep as text */ }

			const record = JSON.stringify({
				id,
				received_at: new Date(now).toISOString(),
				app_version: appVersion || null,
				screen: screen || null,
				description,
				boxes,
				context: parsedContext || null,
				screenshot: image ? `${id}.${ext}` : null,
			}, null, 2);

			const incoming = Buffer.byteLength(record) + (image?.length ?? 0);
			if (used + incoming > maxBytes) {
				return { status: 507, error: 'feedback storage is full' };
			}

			// Image first: a record that names a screenshot must never exist without it.
			if (image) writeAtomic(join(dir, `${id}.${ext}`), image);
			writeAtomic(join(dir, `${id}.json`), record);
			used += incoming;
			return { ok: true, id };
		},
	};
}

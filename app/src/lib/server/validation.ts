import { error } from '@sveltejs/kit';
import { z } from 'zod';

export async function parseAndValidate<T extends z.ZodSchema>(
	request: Request,
	schema: T
): Promise<z.infer<T>> {
	const result = schema.safeParse(await request.json());
	if (!result.success) {
		error(400, { message: 'Validation error', issues: result.error.issues });
	}
	return result.data;
}

/**
 * The Android app's built-in palettes (tfi-app Theme.kt PRESETS), light and dark faces as written
 * there. The page follows the system light/dark setting, as the app does.
 */
interface Face {
	primary: string; onPrimary: string; tertiary: string;
	background: string; surface: string; onSurface: string; onSurfaceVariant: string; error: string;
}

export interface Palette { id: string; name: string; light: Face; dark: Face }

const face = (primary: string, onPrimary: string, tertiary: string, background: string, surface: string, onSurface: string, onSurfaceVariant: string, error: string): Face =>
	({ primary, onPrimary, tertiary, background, surface, onSurface, onSurfaceVariant, error });

export const PALETTES: Palette[] = [
	{
		id: 'purple', name: 'Default (purple)',
		light: face('#6750A4', '#FFFFFF', '#7D5260', '#FFFBFE', '#FFFBFE', '#1C1B1F', '#49454F', '#B3261E'),
		dark: face('#D0BCFF', '#381E72', '#EFB8C8', '#1C1B1F', '#1C1B1F', '#E6E1E5', '#CAC4D0', '#F2B8B5'),
	},
	{
		id: 'blue', name: 'Blue & yellow',
		light: face('#003B8C', '#FFFFFF', '#2E7D32', '#EEF1F7', '#FFFFFF', '#1C1B1F', '#49454F', '#B3261E'),
		dark: face('#4D8AE0', '#001A40', '#81C784', '#101418', '#1A1F26', '#E3E5E8', '#B8BCC2', '#FF6679'),
	},
	{
		id: 'green', name: 'Green',
		light: face('#3D5663', '#FFFFFF', '#2E7D32', '#FFFBFE', '#FFFBFE', '#1C1B1F', '#49454F', '#B3261E'),
		dark: face('#8FB0BF', '#10242E', '#81C784', '#14181C', '#1B1F24', '#E3E5E8', '#B8BCC2', '#F2B8B5'),
	},
	{
		id: 'paper', name: 'Paper',
		light: face('#B05C4A', '#FFFFFF', '#5B8266', '#FDF6EC', '#FFFCF7', '#2A2320', '#7A6A60', '#B3261E'),
		dark: face('#E8A188', '#3A1D14', '#8FBF9F', '#17130F', '#221C17', '#EDE4DA', '#B8A99C', '#F2B8B5'),
	},
];

const dark = matchMedia('(prefers-color-scheme: dark)');

export function applyPalette(id: string) {
	const p = PALETTES.find((x) => x.id === id) ?? PALETTES[0];
	const f = dark.matches ? p.dark : p.light;
	const s = document.documentElement.style;
	s.setProperty('--primary', f.primary);
	s.setProperty('--on-primary', f.onPrimary);
	s.setProperty('--tertiary', f.tertiary);
	s.setProperty('--bg', f.background);
	s.setProperty('--surface', f.surface);
	s.setProperty('--ink', f.onSurface);
	s.setProperty('--muted', f.onSurfaceVariant);
	s.setProperty('--error', f.error);
	s.colorScheme = dark.matches ? 'dark' : 'light';
	document.querySelector('meta[name=theme-color]')?.setAttribute('content', f.primary);
}

export function watchSystemTheme(current: () => string) {
	dark.addEventListener('change', () => applyPalette(current()));
}

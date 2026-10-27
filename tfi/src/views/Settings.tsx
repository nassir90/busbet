import { useEffect, useState } from 'preact/hooks';
import { TopBar } from '../components';
import { useNavReady } from '../router';
import {
	clearHistory, moveFavourite, removeFavourite, updateFavourite, updateSettings,
	useFavourites, useHistory, useSettings,
} from '../store';
import { applyPalette, PALETTES } from '../theme';

export function SettingsView() {
	const settings = useSettings();
	const favourites = useFavourites();
	const history = useHistory();
	const [denied, setDenied] = useState(false);
	useNavReady(true, true);
	useEffect(() => { document.title = 'Settings — Iompar'; }, []);

	const setNearest = (on: boolean) => {
		updateSettings({ sortByDistance: on });
		setDenied(false);
		// Ask now, from the tap, so the browser's prompt is tied to the setting that needs it.
		if (on && 'geolocation' in navigator) {
			navigator.geolocation.getCurrentPosition(() => {}, (e) => {
				if (e.code === e.PERMISSION_DENIED) setDenied(true);
			}, { maximumAge: 60_000, timeout: 20_000 });
		}
	};

	return (
		<>
			<TopBar title="Settings" />
			<main class="page settings">
				<section>
					<h2 class="label">Favourites</h2>
					<label class="setting">
						<span class="setting-text">
							<span>Nearest first</span>
							<span class="code">Sort favourites by distance. Your location stays in this browser; it is never sent anywhere.</span>
						</span>
						<input type="checkbox" class="switch" checked={settings.sortByDistance} onChange={(e) => setNearest((e.target as HTMLInputElement).checked)} />
					</label>
					{denied && <p class="note">Location is blocked for this site in your browser settings.</p>}
					{favourites.length > 0 && (
						<ul class="list edit-list">
							{favourites.map((f, i) => (
								<li key={f.code}>
									<input
										class="rename"
										value={f.name}
										aria-label={`Name for stop ${f.code}`}
										onChange={(e) => updateFavourite(f.code, { name: (e.target as HTMLInputElement).value.trim() || f.code })}
									/>
									<span class="code">{f.code}</span>
									<button class="icon-btn subtle" aria-label="Move up" disabled={i === 0} onClick={() => moveFavourite(f.code, -1)}>↑</button>
									<button class="icon-btn subtle" aria-label="Move down" disabled={i === favourites.length - 1} onClick={() => moveFavourite(f.code, 1)}>↓</button>
									<button class="icon-btn subtle" aria-label="Remove favourite" onClick={() => removeFavourite(f.code)}>✕</button>
								</li>
							))}
						</ul>
					)}
				</section>

				<section>
					<h2 class="label">Display</h2>
					<label class="setting">
						<span class="setting-text">
							<span>Maps</span>
							<span class="code">Show a map with live buses on stop and trip pages. Tiles come from OpenStreetMap.</span>
						</span>
						<input type="checkbox" class="switch" checked={settings.maps} onChange={(e) => updateSettings({ maps: (e.target as HTMLInputElement).checked })} />
					</label>
					<div class="palettes" role="radiogroup" aria-label="Colours">
						{PALETTES.map((p) => (
							<label key={p.id} class={`palette${settings.palette === p.id ? ' on' : ''}`}>
								<input
									type="radio"
									name="palette"
									checked={settings.palette === p.id}
									onChange={() => { updateSettings({ palette: p.id }); applyPalette(p.id); }}
								/>
								<span class="swatch" style={{ background: p.light.primary }} />
								<span>{p.name}</span>
							</label>
						))}
					</div>
				</section>

				<section>
					<h2 class="label">Search history</h2>
					<button class="text-btn" disabled={!history.length} onClick={clearHistory}>
						{history.length ? `Clear ${history.length} recent ${history.length === 1 ? 'search' : 'searches'}` : 'No recent searches'}
					</button>
				</section>

				<section>
					<h2 class="label">About</h2>
					<ul class="list links">
						<li><a class="row" href="app/">Android app</a></li>
						<li><a class="row" href="privacy-policy">Privacy policy</a></li>
					</ul>
					<p class="note">Times come from the National Transport Authority's GTFS and GTFS-Realtime feeds. Everything here is kept in this browser only.</p>
				</section>
			</main>
		</>
	);
}

import { render } from 'preact';
import { useEffect } from 'preact/hooks';
import './styles.css';
import { useView } from './router';
import { getSettings } from './store';
import { applyPalette, watchSystemTheme } from './theme';
import { Home } from './views/Home';
import { RouteView } from './views/Route';
import { StopView } from './views/Stop';
import { SettingsView } from './views/Settings';
import { TripView } from './views/Trip';

applyPalette(getSettings().palette);
watchSystemTheme(() => getSettings().palette);

function App() {
	const v = useView();
	useEffect(() => { if (v.name === 'home') document.title = 'Iompar'; }, [v.name]);
	switch (v.name) {
		// Keyed so a view's state (filters, scroll targets) never leaks from one stop to the next.
		case 'stop': return <StopView key={v.code} code={v.code} />;
		case 'route': return <RouteView key={v.route} route={v.route} dir={v.dir} from={v.from} />;
		case 'trip': return <TripView key={v.id} id={v.id} from={v.from} />;
		case 'settings': return <SettingsView />;
		default: return <Home />;
	}
}

render(<App />, document.getElementById('app')!);

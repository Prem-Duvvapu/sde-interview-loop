import { createRoot } from 'react-dom/client';
import './monaco-setup';
import './styles.css';
import { App } from './App';

/**
 * StrictMode is deliberately not used.
 *
 * Its double-invoked effects would open, close and reopen the interview WebSocket on
 * every mount and double every REST call on the setup screen — which makes the
 * connection state on screen a lie and doubles the noise against a backend that is
 * often not running yet. The socket hook cleans up properly on its own.
 */
const container = document.getElementById('root');
if (!container) {
  throw new Error('Root element #root is missing from index.html');
}

createRoot(container).render(<App />);

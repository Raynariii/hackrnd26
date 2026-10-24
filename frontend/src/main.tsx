import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import '@fontsource/golos-text/400.css';
import '@fontsource/golos-text/500.css';
import '@fontsource/golos-text/600.css';
import '@fontsource/jetbrains-mono/400.css';
import '@fontsource/jetbrains-mono/600.css';
import '@xyflow/react/dist/style.css';
import './styles.css';
import './monaco';
import { App } from './App';

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);

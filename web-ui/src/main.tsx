import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App';
import './i18n';
import './styles/global.css';
import './styles/app.css';
import './styles/terminal.css';
import './styles/crafting.css';

const root = document.getElementById('root');
if (!root) {
  throw new Error('Missing #root element');
}

createRoot(root).render(
  <StrictMode>
    <App />
  </StrictMode>,
);

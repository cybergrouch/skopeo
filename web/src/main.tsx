import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import App from './App.tsx'
import { installGlobalErrorHandlers } from '@/observability/globalHandlers'
import { consoleReporter, noopReporter, setReporter } from '@/observability/reporter'

// The active error reporter (#807). No vendor yet — #751 defers that until the cost question is settled,
// and the seam means adding one is this line plus a single adapter file (#811). Until then production
// routes signals nowhere, while dev logs them so a miswired boundary or handler is visible rather than
// looking identical to one that works.
setReporter(import.meta.env.DEV ? consoleReporter : noopReporter)

// Installed before render, so a failure during the first paint is still captured.
installGlobalErrorHandlers()

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)

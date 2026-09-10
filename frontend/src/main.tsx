import React from 'react'
import ReactDOM from 'react-dom/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { BrowserRouter } from 'react-router-dom'
import App from './App'
import ToastProvider from './components/ui/ToastProvider'
import './styles.css'
import './executive-theme.css'

const queryClient = new QueryClient()

// UI density modes removed (always use default spacing).
document.body.classList.remove('density-compact')
localStorage.removeItem('uiDensity')

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <QueryClientProvider client={queryClient}>
      <ToastProvider>
        <BrowserRouter>
          <App />
        </BrowserRouter>
      </ToastProvider>
    </QueryClientProvider>
  </React.StrictMode>
)

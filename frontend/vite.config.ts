import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173
  },
  build: {
    rollupOptions: {
      output: {
        manualChunks(id) {
          const normalized = id.split('\\').join('/')
          if (!normalized.includes('/node_modules/')) return
          if (normalized.includes('/node_modules/@tanstack/')) return 'query-vendor'
          if (normalized.includes('/node_modules/html2canvas/')) return 'html2canvas-vendor'
          if (normalized.includes('/node_modules/jspdf/')) return 'jspdf-vendor'
          if (normalized.includes('/node_modules/papaparse/')) return 'csv-vendor'
          if (normalized.includes('/node_modules/zrender/')) return 'zrender-vendor'
          if (
            normalized.includes('/node_modules/echarts/lib/chart/heatmap') ||
            normalized.includes('/node_modules/echarts/lib/chart/scatter') ||
            normalized.includes('/node_modules/echarts/lib/component/visualMap')
          ) {
            return 'charts-advanced-vendor'
          }
          if (normalized.includes('/node_modules/echarts/')) return 'charts-vendor'
          return undefined
        }
      }
    }
  }
})

import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { BarChart, HeatmapChart, LineChart } from 'echarts/charts'
import { DataZoomComponent, GridComponent, LegendComponent, TooltipComponent, VisualMapComponent } from 'echarts/components'

use([
  CanvasRenderer,
  BarChart,
  LineChart,
  HeatmapChart,
  GridComponent,
  TooltipComponent,
  LegendComponent,
  VisualMapComponent,
  DataZoomComponent
])

export * from 'echarts/core'


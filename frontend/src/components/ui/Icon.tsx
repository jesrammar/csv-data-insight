type IconName =
  | 'overview'
  | 'home'
  | 'dashboard'
  | 'alerts'
  | 'imports'
  | 'tribunal'
  | 'universal'
  | 'advisor'
  | 'reports'
  | 'pricing'
  | 'automation'
  | 'audit'
  | 'admin'
  | 'help'
  | 'logout'

export default function Icon({ name, size = 18 }: { name: IconName; size?: number }) {
  const common = { width: size, height: size, viewBox: '0 0 24 24', fill: 'none', xmlns: 'http://www.w3.org/2000/svg' as const }
  const stroke = { stroke: 'currentColor', strokeWidth: 2, strokeLinecap: 'round' as const, strokeLinejoin: 'round' as const }

  switch (name) {
    case 'overview':
      return (
        <svg {...common}>
          <path {...stroke} d="M4 4h7v7H4zM13 4h7v7h-7zM4 13h7v7H4zM13 13h7v7h-7z" />
        </svg>
      )
    case 'home':
      return (
        <svg {...common}>
          <path {...stroke} d="M3 10.5l9-7 9 7" />
          <path {...stroke} d="M5 9.5V21h14V9.5" />
          <path {...stroke} d="M9 21v-7h6v7" />
        </svg>
      )
    case 'dashboard':
      return (
        <svg {...common}>
          <path {...stroke} d="M4 19V5M4 19h16M8 15v-4M12 19v-8M16 11v-3M20 8v-3" />
        </svg>
      )
    case 'alerts':
      return (
        <svg {...common}>
          <path {...stroke} d="M12 22a2 2 0 0 0 2-2H10a2 2 0 0 0 2 2z" />
          <path {...stroke} d="M18 16v-5a6 6 0 1 0-12 0v5l-2 2h16l-2-2z" />
        </svg>
      )
    case 'imports':
      return (
        <svg {...common}>
          <path {...stroke} d="M12 3v12" />
          <path {...stroke} d="M7 10l5 5 5-5" />
          <path {...stroke} d="M5 21h14" />
        </svg>
      )
    case 'tribunal':
      return (
        <svg {...common}>
          <path {...stroke} d="M12 3l8 4-8 4-8-4 8-4z" />
          <path {...stroke} d="M4 11v6c0 2 3.6 4 8 4s8-2 8-4v-6" />
        </svg>
      )
    case 'universal':
      return (
        <svg {...common}>
          <path {...stroke} d="M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20z" />
          <path {...stroke} d="M2 12h20" />
          <path {...stroke} d="M12 2c3 3 3 17 0 20" />
        </svg>
      )
    case 'advisor':
      return (
        <svg {...common}>
          <path {...stroke} d="M21 15a4 4 0 0 1-4 4H8l-5 3V7a4 4 0 0 1 4-4h10a4 4 0 0 1 4 4z" />
          <path {...stroke} d="M7 8h10M7 12h7" />
        </svg>
      )
    case 'reports':
      return (
        <svg {...common}>
          <path {...stroke} d="M7 3h8l3 3v15a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z" />
          <path {...stroke} d="M15 3v4h4" />
          <path {...stroke} d="M8 13h8M8 17h8M8 9h4" />
        </svg>
      )
    case 'pricing':
      return (
        <svg {...common}>
          <path {...stroke} d="M12 1v22" />
          <path {...stroke} d="M17 5H9.5a3.5 3.5 0 0 0 0 7H14a3.5 3.5 0 0 1 0 7H6" />
        </svg>
      )
    case 'automation':
      return (
        <svg {...common}>
          <path {...stroke} d="M12 15.5a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7z" />
          <path
            {...stroke}
            d="M19.4 15a8 8 0 0 0 .1-1l2-1.2-2-3.4-2.3.7a7.9 7.9 0 0 0-1.7-1L15 6h-6l-.5 2.1a7.9 7.9 0 0 0-1.7 1L4 8.4 2 11.8l2 1.2a8 8 0 0 0 0 2L2 16.2l2 3.4 2.3-.7a7.9 7.9 0 0 0 1.7 1L9 22h6l.5-2.1a7.9 7.9 0 0 0 1.7-1l2.3.7 2-3.4-2.1-1.2z"
          />
        </svg>
      )
    case 'audit':
      return (
        <svg {...common}>
          <path {...stroke} d="M12 3l8 4v6c0 5-3.5 9-8 9s-8-4-8-9V7l8-4z" />
          <path {...stroke} d="M9 12h6" />
          <path {...stroke} d="M9 16h6" />
        </svg>
      )
    case 'admin':
      return (
        <svg {...common}>
          <path {...stroke} d="M12 2l2.5 5 5.5.8-4 3.9.9 5.6-4.9-2.6-4.9 2.6.9-5.6-4-3.9L9.5 7 12 2z" />
          <path {...stroke} d="M8 21h8" />
        </svg>
      )
    case 'help':
      return (
        <svg {...common}>
          <path {...stroke} d="M12 18h.01" />
          <path {...stroke} d="M9.1 9a3 3 0 1 1 4.9 2.3c-.8.6-1.5 1.1-1.5 2.2v.5" />
          <path {...stroke} d="M12 22A10 10 0 1 0 12 2a10 10 0 0 0 0 20z" />
        </svg>
      )
    case 'logout':
      return (
        <svg {...common}>
          <path {...stroke} d="M10 16l-4-4 4-4" />
          <path {...stroke} d="M6 12h10" />
          <path {...stroke} d="M14 4h4a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-4" />
        </svg>
      )
    default:
      return null as never
  }
}

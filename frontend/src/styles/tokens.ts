/**
 * STOCKGUARD Design Tokens
 * Single source of truth for Mineral & Signal palettes, elevation, spacing, motion, and typography.
 */

export const tokens = {
  colors: {
    // Mineral & Signal Brand Palette
    brand: {
      mineralForest: '#456b1f',
      signalLime: '#c6f36b',
      deepTeal: '#147c68',
      signalMint: '#6cd8c0',
      carbonBasalt: '#0b100f',
      deepPine: '#111a17',
      warmLimestone: '#f4f5ef',
      porcelain: '#ffffff',
    },
    semantic: {
      success: {
        bgLight: '#ecfdf5',
        textLight: '#065f46',
        borderLight: '#a7f3d0',
        bgDark: 'rgba(198, 243, 107, 0.12)',
        textDark: '#c6f36b',
        borderDark: 'rgba(198, 243, 107, 0.3)',
      },
      warning: {
        bgLight: '#fffbeb',
        textLight: '#92400e',
        borderLight: '#fde68a',
        bgDark: 'rgba(245, 158, 11, 0.12)',
        textDark: '#fbbf24',
        borderDark: 'rgba(245, 158, 11, 0.3)',
      },
      danger: {
        bgLight: '#fef2f2',
        textLight: '#991b1b',
        borderLight: '#fecaca',
        bgDark: 'rgba(244, 63, 94, 0.12)',
        textDark: '#fb7185',
        borderDark: 'rgba(244, 63, 94, 0.3)',
      },
      info: {
        bgLight: '#f0fdf4',
        textLight: '#166534',
        borderLight: '#bbf7d0',
        bgDark: 'rgba(108, 216, 192, 0.12)',
        textDark: '#6cd8c0',
        borderDark: 'rgba(108, 216, 192, 0.3)',
      },
    },
    theme: {
      light: {
        canvas: '#f4f5ef',
        surface: '#ffffff',
        subtle: '#f0f4ee',
        elevated: '#e9eee6',
        textPrimary: '#18231d',
        textSecondary: '#657369',
        textMuted: '#8a988e',
        borderSubtle: 'rgba(24, 35, 29, 0.08)',
        borderDefault: '#d9e1d7',
        borderAccent: 'rgba(69, 107, 31, 0.4)',
      },
      dark: {
        canvas: '#0b100f',
        surface: '#111a17',
        subtle: '#16221e',
        elevated: '#1b2922',
        textPrimary: '#eef3ea',
        textSecondary: '#9aa99e',
        textMuted: '#6c7d71',
        borderSubtle: 'rgba(238, 243, 234, 0.08)',
        borderDefault: '#26362e',
        borderAccent: 'rgba(198, 243, 107, 0.35)',
      },
    },
  },
  shadows: {
    cardLight: '0 1px 3px rgba(24, 35, 29, 0.04), 0 1px 2px rgba(24, 35, 29, 0.02)',
    cardDark: '0 4px 20px rgba(0, 0, 0, 0.35)',
    elevatedLight: '0 10px 25px -5px rgba(24, 35, 29, 0.07), 0 8px 10px -6px rgba(24, 35, 29, 0.03)',
    elevatedDark: '0 12px 32px rgba(0, 0, 0, 0.55)',
    glowLime: '0 0 25px rgba(198, 243, 107, 0.15)',
    glowTeal: '0 0 25px rgba(108, 216, 192, 0.15)',
  },
  radii: {
    xs: '0.25rem', // 4px
    sm: '0.375rem', // 6px
    md: '0.5rem', // 8px
    lg: '0.75rem', // 12px
    xl: '1rem', // 16px
    full: '9999px',
  },
  motion: {
    easeFluid: 'cubic-bezier(0.16, 1, 0.3, 1)',
    easeSpring: 'cubic-bezier(0.34, 1.56, 0.64, 1)',
    durationFast: '140ms',
    durationNormal: '240ms',
    durationSlow: '380ms',
  },
} as const;

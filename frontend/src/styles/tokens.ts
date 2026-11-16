/**
 * STOCKGUARD Design Tokens
 * Single source of truth for color palettes, elevation, spacing, and typography.
 */

export const tokens = {
  colors: {
    brand: {
      cyan: {
        50: '#ecfeff',
        100: '#cffafe',
        200: '#a5f3fc',
        300: '#67e8f9',
        400: '#22d3ee',
        500: '#06b6d4',
        600: '#0891b2',
        700: '#0e7490',
        800: '#155e75',
        900: '#164e63',
      },
      indigo: {
        50: '#eef2ff',
        100: '#e0e7ff',
        200: '#c7d2fe',
        300: '#a5b4fc',
        400: '#818cf8',
        500: '#6366f1',
        600: '#4f46e5',
        700: '#4338ca',
        800: '#3730a3',
        900: '#312e81',
      },
    },
    semantic: {
      success: {
        bgLight: '#ecfdf5',
        textLight: '#065f46',
        bgDark: 'rgba(16, 185, 129, 0.15)',
        textDark: '#34d399',
        borderDark: 'rgba(16, 185, 129, 0.3)',
      },
      warning: {
        bgLight: '#fffbeb',
        textLight: '#92400e',
        bgDark: 'rgba(245, 158, 11, 0.15)',
        textDark: '#fbbf24',
        borderDark: 'rgba(245, 158, 11, 0.3)',
      },
      danger: {
        bgLight: '#fef2f2',
        textLight: '#991b1b',
        bgDark: 'rgba(244, 63, 94, 0.15)',
        textDark: '#fb7185',
        borderDark: 'rgba(244, 63, 94, 0.3)',
      },
      info: {
        bgLight: '#f0f9ff',
        textLight: '#075985',
        bgDark: 'rgba(14, 165, 233, 0.15)',
        textDark: '#38bdf8',
        borderDark: 'rgba(14, 165, 233, 0.3)',
      },
    },
    theme: {
      light: {
        canvas: '#f8fafc',
        surface: '#ffffff',
        subtle: '#f1f5f9',
        elevated: '#ffffff',
        textPrimary: '#0f172a',
        textSecondary: '#475569',
        textMuted: '#64748b',
        borderSubtle: 'rgba(0, 0, 0, 0.06)',
        borderDefault: 'rgba(0, 0, 0, 0.12)',
        borderAccent: 'rgba(8, 145, 178, 0.35)',
      },
      dark: {
        canvas: '#080c14',
        surface: '#0e1524',
        subtle: '#131c2e',
        elevated: '#19253d',
        textPrimary: '#f8fafc',
        textSecondary: '#cbd5e1',
        textMuted: '#94a3b8',
        borderSubtle: 'rgba(255, 255, 255, 0.08)',
        borderDefault: 'rgba(255, 255, 255, 0.14)',
        borderAccent: 'rgba(6, 182, 212, 0.35)',
      },
    },
  },
  shadows: {
    cardLight: '0 1px 3px rgba(0,0,0,0.06), 0 1px 2px rgba(0,0,0,0.04)',
    cardDark: '0 4px 20px rgba(0,0,0,0.3)',
    glow: '0 0 25px rgba(6, 182, 212, 0.15)',
    glowAccent: '0 0 35px rgba(99, 102, 241, 0.2)',
  },
  radii: {
    sm: '0.375rem',
    md: '0.5rem',
    lg: '0.75rem',
    xl: '1rem',
    full: '9999px',
  },
} as const;

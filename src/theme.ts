import type { MobilePreferences } from './native/contract'

export function applyTheme(theme: NonNullable<MobilePreferences['theme']>, systemDark: boolean) {
  const root = document.documentElement
  if (theme === 'classic') { delete root.dataset.theme; delete root.dataset.systemScheme }
  else {
    root.dataset.theme = theme
    if (theme === 'system') root.dataset.systemScheme = systemDark ? 'dark' : 'light'
    else delete root.dataset.systemScheme
  }
}

import type { LyricsProvider } from '../lyric-types'
import type { MobilePreferences } from '../native/contract'

export const defaultPreferences = (): MobilePreferences => ({ version: 1, lyricsProvider: 'bilibili', lyricOffsets: {} })

export function normalizePreferences(value: unknown): MobilePreferences {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('设置格式异常，原记录已保留。')
  const input = value as Record<string, unknown>
  if (input.version !== 1 || !input.lyricOffsets || typeof input.lyricOffsets !== 'object' || Array.isArray(input.lyricOffsets)) throw new Error('设置版本或格式异常，原记录已保留。')
  const lyricOffsets: Record<string, number> = {}
  for (const [key, offset] of Object.entries(input.lyricOffsets).slice(0, 3000)) {
    if (/^(?:bilibili|lrclib):BV[0-9A-Za-z]{10}$/.test(key) && typeof offset === 'number' && Number.isFinite(offset)) lyricOffsets[key] = Math.round(Math.min(120, Math.max(-120, offset)) * 100) / 100
  }
  return { version: 1, lyricsProvider: input.lyricsProvider === 'lrclib' ? 'lrclib' : 'bilibili', lyricOffsets }
}

export function getLyricOffset(preferences: MobilePreferences, provider: LyricsProvider, bvid: string): number {
  const offset = preferences.lyricOffsets[`${provider}:${bvid}`]
  return Number.isFinite(offset) ? offset : provider === 'bilibili' ? -0.25 : 0
}

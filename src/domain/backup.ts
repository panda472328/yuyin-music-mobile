import { parseLibraryBackup, type LibraryState } from './library'
import { normalizePreferences } from './preferences'
import type { MobilePreferences, FloatingLyricsSettings } from '../native/contract'

export interface MobileBackup { library: LibraryState; preferences?: MobilePreferences; floatingLyrics?: FloatingLyricsSettings }
export function normalizeFloating(value: unknown): FloatingLyricsSettings {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('悬浮歌词备份格式不正确。')
  const raw = value as Record<string, unknown>
  const numeric = (key: string, min: number, max: number, fallback: number) => typeof raw[key] === 'number' && Number.isFinite(raw[key]) ? Math.min(max, Math.max(min, raw[key] as number)) : fallback
  return { enabled: raw.enabled === true, locked: raw.locked === true, font: ['sans', 'serif', 'kai', 'rounded'].includes(String(raw.font)) ? raw.font as FloatingLyricsSettings['font'] : 'sans', color: ['white', 'mint', 'gold'].includes(String(raw.color)) ? raw.color as FloatingLyricsSettings['color'] : 'white', fontSize: numeric('fontSize', 16, 40, 24), opacity: numeric('opacity', .3, 1, 1), x: numeric('x', 0, 1, .5), y: numeric('y', 0, 1, .2), permission: false }
}
export const MAX_BACKUP_BYTES = 8 * 1024 * 1024
export function parseBackup(text: string): MobileBackup {
  if (typeof text !== 'string' || new TextEncoder().encode(text).length > MAX_BACKUP_BYTES) throw new Error('备份文件超过 8 MB，未导入任何内容。')
  let input: unknown
  try { input = JSON.parse(text.replace(/^\uFEFF/, '')) } catch { throw new Error('备份文件不是有效 JSON。') }
  if (!input || typeof input !== 'object' || Array.isArray(input)) throw new Error('备份格式不正确。')
  const value = input as Record<string, unknown>
  if (value.kind === 'yuyin-mobile-backup') {
    if (value.schemaVersion !== 1 || value.platform !== 'android') throw new Error('备份版本或平台不支持。')
    if (!value.preferences || typeof value.preferences !== 'object' || Array.isArray(value.preferences) || (value.preferences as Record<string, unknown>).version !== 1) throw new Error('备份设置格式不正确。')
    return { library: parseLibraryBackup(value.library), preferences: normalizePreferences(value.preferences), ...(value.floatingLyrics ? { floatingLyrics: normalizeFloating(value.floatingLyrics) } : {}) }
  }
  return { library: parseLibraryBackup(value) }
}
export function createBackup(library: LibraryState, preferences: MobilePreferences, floatingLyrics?: FloatingLyricsSettings): string {
  const text = JSON.stringify({ kind: 'yuyin-mobile-backup', schemaVersion: 1, platform: 'android', exportedAt: new Date().toISOString(), library: parseLibraryBackup(library), preferences: normalizePreferences(preferences), ...(floatingLyrics ? { floatingLyrics: normalizeFloating(floatingLyrics) } : {}) }, null, 2)
  if (new TextEncoder().encode(text).length > MAX_BACKUP_BYTES) throw new Error('备份超过 8 MB，请减少歌库内容后重试。')
  return text
}

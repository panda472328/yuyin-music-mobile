import type { BilibiliFavoriteFolder, SearchResult, Song } from '../types'
import { BilibiliError, safeAccountAvatar } from './account'

export const record = (value: unknown): Record<string, unknown> | null => value !== null && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : null
export const positiveInteger = (value: unknown): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value > 0

export function plainText(value: unknown): string {
  if (typeof value !== 'string') return ''
  const named: Record<string, string> = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", nbsp: ' ' }
  return value.replace(/<[^>]*>/g, '').replace(/&(#x[0-9a-f]+|#\d+|amp|lt|gt|quot|apos|nbsp);/gi, (_match, entity: string) => {
    if (!entity.startsWith('#')) return named[entity.toLowerCase()] ?? ''
    const numeric = entity[1]?.toLowerCase() === 'x' ? Number.parseInt(entity.slice(2), 16) : Number.parseInt(entity.slice(1), 10)
    return Number.isFinite(numeric) && numeric >= 0 && numeric <= 0x10ffff ? String.fromCodePoint(numeric) : ''
  }).replace(/[\u0000-\u001f\u007f]/g, ' ').trim().slice(0, 1000)
}

function durationSeconds(value: unknown): number {
  if (typeof value === 'number') return Number.isFinite(value) ? Math.min(604800, Math.max(0, value)) : 0
  if (typeof value !== 'string') return 0
  const parts = value.split(':').map(Number)
  return parts.length <= 3 && parts.every(part => Number.isFinite(part) && part >= 0) ? Math.min(604800, parts.reduce((total, part) => total * 60 + part, 0)) : 0
}

function playCount(value: unknown): number {
  if (typeof value === 'number') return Number.isFinite(value) ? Math.max(0, value) : 0
  if (typeof value !== 'string') return 0
  const parsed = Number.parseFloat(value.replace(/,/g, ''))
  return Number.isFinite(parsed) ? Math.max(0, Math.round(parsed * (value.includes('亿') ? 100000000 : value.includes('万') ? 10000 : 1))) : 0
}

export function videoToSong(value: unknown, query?: string): Song | null {
  const item = record(value)
  if (!item) return null
  const bvid = item.bvid ?? item.bv_id
  if (typeof bvid !== 'string' || !/^BV[0-9A-Za-z]{10}$/.test(bvid)) return null
  return {
    id: bvid, bvid, title: plainText(item.title) || bvid,
    artist: plainText(record(item.upper)?.name ?? item.author) || 'Bilibili UP 主',
    cover: safeAccountAvatar(item.cover ?? item.pic), duration: durationSeconds(item.duration),
    playCount: playCount(item.play), source: 'bilibili', url: `https://www.bilibili.com/video/${bvid}/`,
    ...(query ? { searchQuery: query } : {}),
  }
}

export function apiData(value: unknown): Record<string, unknown> {
  const payload = record(value)
  if (payload?.code !== 0) {
    const code = Number(payload?.code)
    const restricted = [-101, -111, -352, -403, -412, -509].includes(code)
    throw new BilibiliError(restricted ? 'Bilibili 要求登录或验证，请打开源站完成后重试。' : `Bilibili 服务响应异常（${Number.isSafeInteger(code) ? code : '格式异常'}）。`, restricted ? 'BILIBILI_VERIFICATION_REQUIRED' : 'BILIBILI_API_ERROR', restricted)
  }
  const data = record(payload.data)
  if (!data) throw new BilibiliError('Bilibili 返回的数据格式异常。', 'BILIBILI_INVALID_RESPONSE')
  return data
}

export function parseSearch(value: unknown, query: string, page: number): SearchResult {
  const data = apiData(value)
  if (!Array.isArray(data.result) || data.result.length > 100) throw new BilibiliError('Bilibili 搜索结果格式异常。', 'BILIBILI_INVALID_RESPONSE')
  // Preserve official totalrank order; there is deliberately no auto-play or local re-sort.
  const songs = data.result.map(item => videoToSong(item, query)).filter((song): song is Song => song !== null)
  const total = Math.max(0, Number(data.numResults) || songs.length)
  const pageSize = Math.min(100, Math.max(1, Number(data.pagesize) || 20))
  const pages = Number(data.numPages) || Math.ceil(total / pageSize)
  return { query, songs, page, pageSize, total, hasMore: page < pages }
}

export function parseFolder(value: unknown, fallbackId?: number): BilibiliFavoriteFolder | null {
  const folder = record(value)
  const id = folder?.id ?? fallbackId
  if (!folder || !positiveInteger(id)) return null
  const title = plainText(folder.title) || `收藏夹 ${id}`
  return { id, title, mediaCount: Math.max(0, Number(folder.media_count) || 0), cover: safeAccountAvatar(folder.cover), description: plainText(folder.intro), isDefault: Number(folder.attr) === 22 || title === '默认收藏夹' }
}

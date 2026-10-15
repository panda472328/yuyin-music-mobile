import assert from 'node:assert/strict'
import test from 'node:test'
import { createBackup, parseBackup, MAX_BACKUP_BYTES } from '../src/domain/backup'
import { initializeLibrary, mergeLibraryBackup, parseLibraryBackup, removeFromQueue, type LibraryState } from '../src/domain/library'
import { defaultPreferences, normalizePreferences } from '../src/domain/preferences'
import type { Song } from '../src/types'
import { LYRIC_STYLE_OPTIONS } from '../src/shared/lyric-styles'

const song = (id: number): Song => { const bvid = `BV${String(id).padStart(10, '0')}`; return { id: bvid, bvid, title: `虚构歌曲 ${id}`, artist: '虚构歌手', cover: '', duration: 200, playCount: 10, source: 'bilibili', url: `https://www.bilibili.com/video/${bvid}/` } }
const library = (): LibraryState => initializeLibrary({ getItem: () => null, setItem: () => {} }).state

test('independent lyric styles survive backups, keep font and timing, and migrate old records', () => {
  for (const { value: lyricStyle } of LYRIC_STYLE_OPTIONS) {
    const preferences = { ...defaultPreferences(), theme: 'bilibili' as const, lyricStyle, lyricOffsets: { 'bilibili:BV0000000001': -.4 } }
    const floating = { enabled: false, locked: false, style: 'minecraft' as const, font: 'serif' as const, fontSize: 32, color: 'gold' as const, opacity: .6, x: .3, y: .4, permission: false }
    const restored = parseBackup(createBackup(library(), preferences, floating))
    assert.deepEqual(restored.preferences, preferences)
    assert.deepEqual(restored.floatingLyrics, floating)
    assert.equal(normalizePreferences({ ...preferences, theme: 'pixel' }).lyricStyle, lyricStyle)
  }
  assert.equal(normalizePreferences({ version: 1, lyricOffsets: {} }).lyricStyle, 'classic')
  assert.equal(normalizePreferences({ ...defaultPreferences(), lyricStyle: 'unknown' }).lyricStyle, 'classic')
  const old = JSON.parse(createBackup(library(), defaultPreferences(), { enabled: false, locked: false, font: 'serif', fontSize: 28, color: 'mint', opacity: .5, x: .2, y: .6, permission: false }))
  delete old.preferences.lyricStyle; delete old.floatingLyrics.style
  assert.equal(parseBackup(JSON.stringify(old)).preferences?.lyricStyle, 'classic')
  assert.equal(parseBackup(JSON.stringify(old)).floatingLyrics?.style, 'classic')
  assert.equal(parseBackup(JSON.stringify(old)).floatingLyrics?.font, 'serif')
})

test('PC bare JSON and mobile backup preserve music, mute volume, theme and calibrated lyrics without credentials', () => {
  const state = { ...library(), favorites: [song(1)], settings: { volume: 0, volumeBeforeMute: .45, playMode: 'shuffle' as const, autoPlayFirst: false } }
  const prefs = { ...defaultPreferences(), theme: 'pixel' as const, lyricOffsets: { 'bilibili:BV0000000001': -.4 } }
  const floating = { enabled: true, locked: true, font: 'serif' as const, fontSize: 28, color: 'mint' as const, opacity: .7, x: .3, y: .6, permission: true, cookie: 'synthetic-secret' }
  const text = createBackup(state, { ...prefs, cookie: 'synthetic-secret' } as typeof prefs, floating)
  const restored = parseBackup(text)
  assert.deepEqual(restored.library.settings, state.settings)
  assert.deepEqual(restored.preferences, prefs)
  assert.equal(restored.floatingLyrics?.permission, false)
  assert.equal(restored.floatingLyrics?.font, 'serif')
  assert.equal(text.includes('synthetic-secret'), false)
  assert.deepEqual(parseBackup(JSON.stringify(state)).library, restored.library)
  assert.deepEqual(parseBackup('\uFEFF' + JSON.stringify(state)).library, restored.library)
})

test('backup merge deduplicates songs, preserves existing metadata, keeps newest history, settings are opt-in', () => {
  const current = { ...library(), favorites: [song(1)], queue: [song(1)], history: [{ song: song(1), playedAt: 20 }] }
  current.playlists[0].songs = [song(1)]
  const imported = { ...library(), favorites: [{ ...song(1), title: '旧备份标题' }, song(2)], queue: [song(2)], history: [{ song: song(1), playedAt: 10 }, { song: song(2), playedAt: 30 }], settings: { volume: 0, volumeBeforeMute: .6, playMode: 'repeat' as const, autoPlayFirst: false } }
  imported.playlists[0].songs = [song(2)]
  const before = JSON.stringify(current)
  const merged = mergeLibraryBackup(current, imported)
  assert.equal(merged.favorites.length, 2)
  assert.equal(merged.favorites.find(item => item.bvid === song(1).bvid)?.title, song(1).title)
  assert.equal(merged.playlists[0].songs.length, 2)
  assert.deepEqual(merged.queue.map(item => item.bvid), [song(1).bvid, song(2).bvid])
  assert.deepEqual(merged.history.map(item => item.playedAt), [30, 20])
  assert.equal(merged.settings.volume, .7)
  assert.equal(mergeLibraryBackup(current, imported, true).settings.volumeBeforeMute, .6)
  assert.equal(JSON.stringify(current), before)
})

test('invalid schema, malformed songs and oversize backups cannot silently clear the library', () => {
  for (const value of ['invalid', 'null', '[]', '{}', JSON.stringify({ ...library(), version: 2 }), JSON.stringify({ ...library(), favorites: null }), JSON.stringify({ kind: 'yuyin-mobile-backup', schemaVersion: 2, platform: 'android', library: library() })]) assert.throws(() => parseBackup(value))
  assert.throws(() => parseBackup(' '.repeat(MAX_BACKUP_BYTES + 1)))
  assert.throws(() => parseLibraryBackup({ ...library(), favorites: [{ bvid: 'invalid', source: 'bilibili' }] }))
  const state = { ...library(), favorites: Array.from({ length: 3000 }, (_, i) => song(i)), queue: [song(1), song(2)] }
  assert.throws(() => mergeLibraryBackup(state, { ...library(), favorites: [song(3001)] }), /容量/)
  const removed = removeFromQueue(state, song(1).bvid)
  assert.equal(removed.queue.length, 1)
  assert.equal(removed.favorites.length, 3000)
  assert.equal(state.queue.length, 2)
})

test('older libraries migrate the saved nonzero volume for mute restoration and keep manual search playback', () => {
  const restored = parseLibraryBackup({ ...library(), settings: { volume: .3, playMode: 'sequence', autoPlayFirst: true } })
  assert.equal(restored.settings.volumeBeforeMute, .3)
  assert.equal(restored.settings.autoPlayFirst, false)
})

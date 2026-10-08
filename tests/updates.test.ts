import assert from 'node:assert/strict'
import test from 'node:test'
import { normalizeUpdateState, shouldCheckUpdate, updateCheckInterval } from '../src/domain/updates'
const available = {
  state: 'available', currentVersion: '0.1.2', currentVersionCode: 3,
  update: { version: '0.1.3', versionCode: 4, url: 'https://github.com/panda472328/yuyin-music-mobile/releases/download/android-v0.1.3/Yuyin-Mobile-0.1.3.apk', releaseNotesUrl: 'https://github.com/panda472328/yuyin-music-mobile/releases/tag/android-v0.1.3', size: 8000000, notes: '测试更新说明', publishedAt: '2026-10-08T00:00:00.000Z' },
}
test('trusted update states keep current identity and only a newer Android release', () => {
  assert.deepEqual(normalizeUpdateState(available), available)
  assert.equal(normalizeUpdateState({ ...available, state: 'downloading', progress: 47 }).progress, 47)
  for (const versionCode of [3, 2, 4.5, Infinity]) assert.throws(() => normalizeUpdateState({ ...available, update: { ...available.update, versionCode } }))
})
test('update bridge response cannot offer foreign URLs, mismatched filenames or unbounded data', () => {
  for (const url of ['https://attacker.example/app.apk', available.update.url + '?x=1', available.update.url.replace('android-v0.1.3', 'android-v0.1.4')]) assert.throws(() => normalizeUpdateState({ ...available, update: { ...available.update, url } }))
  assert.throws(() => normalizeUpdateState({ ...available, update: { ...available.update, releaseNotesUrl: 'javascript:alert(1)' } }))
  for (const size of [0, -1, Infinity, 200000001]) assert.throws(() => normalizeUpdateState({ ...available, update: { ...available.update, size } }))
  assert.throws(() => normalizeUpdateState({ ...available, update: { ...available.update, notes: 'x'.repeat(8001) } }))
  assert.throws(() => normalizeUpdateState({ ...available, update: undefined }))
  assert.throws(() => normalizeUpdateState({ ...available, progress: 101 }))
  assert.throws(() => normalizeUpdateState({ ...available, revision: -1 }))
  assert.throws(() => normalizeUpdateState({ ...available, update: { ...available.update, publishedAt: 'invalid date' } }))
  assert.throws(() => normalizeUpdateState({ ...available, update: { ...available.update, version: '1000000.1.3' } }))
})
test('periodic checks start once and wait six hours, preserving active downloads and installers', () => {
  assert.equal(shouldCheckUpdate(0, 10, 'idle'), true)
  assert.equal(shouldCheckUpdate(100, 100 + updateCheckInterval - 1, 'upToDate'), false)
  assert.equal(shouldCheckUpdate(100, 100 + updateCheckInterval, 'error'), true)
  for (const state of ['checking', 'downloading', 'ready', 'permissionRequired', 'installing'] as const) assert.equal(shouldCheckUpdate(0, 100 + updateCheckInterval, state), false)
})

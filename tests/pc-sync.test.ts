import assert from 'node:assert/strict'
import test from 'node:test'
import { createSearchApi } from '../src/domain/search'
import { normalizePreferences } from '../src/domain/preferences'
import { createCommentsApi, parseVideoComment } from '../src/domain/comments'
import type { YuyinNativePlugin } from '../src/native/contract'
import { preferredMusicUploaderMids, rankSongSearchResults } from '../src/shared/search-mode'

const bvid = 'BV1234567890'
const reply = { rpid: 11, oid: 123, root: 0, parent: 0, ctime: 1, like: 2, rcount: 0, action: 0, content: { message: '虚构评论' }, member: { mid: '5', uname: '测试用户', avatar: 'https://evil.invalid/image' } }
const nav = { code: 0, data: { wbi_img: { img_url: 'https://i0.hdslb.com/bfs/wbi/7cd084941338484aae1ad9425b84077c.png', sub_url: 'https://i0.hdslb.com/bfs/wbi/4932caff0ff746eab6f01bf08b70ac45.png' } } }
test('old and unknown theme records preserve lyric calibrations while adopting classic and song defaults', () => {
  const old = { version: 1, lyricsProvider: 'lrclib', lyricOffsets: { [`lrclib:${bvid}`]: 1.25 } }
  assert.deepEqual(normalizePreferences(old), { ...old, theme: 'classic', searchMode: 'song' })
  assert.equal(normalizePreferences({ ...old, theme: 'future', searchMode: 'bad' }).theme, 'classic')
  for (const theme of ['classic', 'bilibili', 'system', 'pixel']) assert.equal(normalizePreferences({ ...old, theme, searchMode: 'video' }).theme, theme)
})
test('preferred uploaders rank non-DJ singles by views, then collections and other results in one list', () => {
  const mid = preferredMusicUploaderMids[0]
  const songs = [{ title: '测试歌 DJ版', playCount: 10000, uploaderMid: mid }, { title: '测试歌 合集', playCount: 999 }, { title: '测试歌 原版', playCount: 1, uploaderMid: mid }, { title: '测试歌 高音质', playCount: 10, uploaderMid: mid }]
  assert.deepEqual(rankSongSearchResults(songs, '测试歌'), [songs[3], songs[2], songs[1], songs[0]])
})
test('song search recalls uploader pages and deduplicates while video search preserves official order', async () => {
  const calls: URL[] = []
  const search = createSearchApi(async address => {
    const url = new URL(address); calls.push(url)
    if (url.pathname.endsWith('/nav')) return nav
    if (url.pathname.endsWith('/arc/search')) return { code: 0, data: { list: { vlist: [{ bvid, title: '测试歌', author: '音乐测试', length: '03:20', play: 500 }] } } }
    return { code: 0, data: { result: [{ bvid: 'BV0987654321', title: '测试歌 DJ版' }, { bvid, title: '测试歌', mid: preferredMusicUploaderMids[0], play: 500 }] } }
  }, () => 0)
  assert.equal((await search.search('测试歌', 1, 'song')).songs[0].bvid, bvid)
  assert.equal(calls.filter(url => url.pathname.endsWith('/arc/search')).length, 4)
  assert.ok(calls.filter(url => url.pathname.endsWith('/arc/search')).every(url => url.searchParams.has('w_rid')))
  calls.length = 0
  assert.equal((await search.search('测试歌', 1, 'video')).songs[0].bvid, 'BV0987654321')
  assert.equal(calls.length, 1)
})
test('uploader outages do not discard official results or launch playback', async () => {
  const search = createSearchApi(async address => {
    if (!address.includes('/search/type')) throw new Error('controlled optional outage')
    return { code: 0, data: { result: [{ bvid, title: '测试歌' }] } }
  }, () => 0)
  assert.equal((await search.search('测试歌')).songs[0].bvid, bvid)
})
test('comments validate video membership, text and safe avatars; malformed lists are rejected', async () => {
  assert.equal(parseVideoComment(reply, '123').author.avatar, '')
  assert.throws(() => parseVideoComment({ ...reply, oid: 999 }, '123'), /所属视频/)
  const api = createCommentsApi(async address => address.includes('/view') ? { code: 0, data: { bvid, aid: 123 } } : { code: 0, data: { replies: {}, page: { num: 1, size: 20, count: 1 } } }, {} as YuyinNativePlugin, () => 0)
  await assert.rejects(api.list(bvid, 'hot', 1), /评论列表/)
})
test('comment writes delegate identity to native and reject invalid parameters before sending', async () => {
  const calls: unknown[] = []
  const api = createCommentsApi(async () => ({}), { mutateComment: async args => { calls.push(args); return { oid: '123', data: { reply } } } } as YuyinNativePlugin, () => 0)
  assert.equal((await api.create({ bvid, message: '测试发送', expectedMid: 5 })).comment?.id, '11')
  await assert.rejects(api.like(bvid, '11', true, 0), /参数无效/)
  await assert.rejects(api.create({ bvid, message: 'x'.repeat(1001), expectedMid: 5 }), /参数无效/)
  assert.equal(calls.length, 1)
})
test('late comment mutation from an obsolete account cannot update the new account UI', async () => {
  let generation = 0
  const api = createCommentsApi(async () => ({}), { mutateComment: async () => { generation++; return { oid: '123', data: {} } } }, () => generation)
  await assert.rejects(api.like(bvid, '11', true, 5), /账号已变化/)
})

import type { YuyinNativePlugin } from '../native/contract'
import type { CommentsAPI } from '../comments-controller'
import type { VideoComment } from '../shared/comments'
import { apiData, plainText, positiveInteger, record } from './bilibili'
import { safeAccountAvatar } from './account'

const API = 'https://api.bilibili.com'
const validId = (value: unknown): value is string => typeof value === 'string' && /^[1-9][0-9]{0,18}$/.test(value)
const id = (value: unknown, zero = false): string => {
  const text = typeof value === 'number' && Number.isSafeInteger(value) ? String(value) : typeof value === 'string' ? value : ''
  if (validId(text) || zero && text === '0') return text
  throw new Error('评论标识格式异常，请重新获取。')
}
const count = (value: unknown): number => typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 ? value : 0
function validate(bvid: string, page = 1) {
  if (!/^BV[0-9A-Za-z]{10}$/.test(bvid) || !Number.isSafeInteger(page) || page < 1 || page > 1000) throw new Error('评论区参数无效。')
}

export function parseVideoComment(value: unknown, oid: string, nested = false): VideoComment {
  const raw = record(value), member = record(raw?.member), content = record(raw?.content)
  if (!raw || !member || !content || id(raw.oid) !== oid) throw new Error('评论所属视频不一致，请重新获取。')
  const mid = Number(member.mid)
  if (!positiveInteger(mid) || typeof content.message !== 'string' || content.message.length > 20000) throw new Error('评论内容格式异常。')
  return { id: id(raw.rpid_str ?? raw.rpid), rootId: id(raw.root_str ?? raw.root ?? 0, true), parentId: id(raw.parent_str ?? raw.parent ?? 0, true),
    author: { mid, username: plainText(member.uname) || 'Bilibili 用户', avatar: safeAccountAvatar(member.avatar) },
    message: content.message.replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/g, ''), createdAt: count(raw.ctime), likeCount: count(raw.like), replyCount: count(raw.rcount), liked: raw.action === 1,
    replies: !nested && Array.isArray(raw.replies) ? raw.replies.slice(0, 20).map(reply => parseVideoComment(reply, oid, true)) : [],
  }
}
function comments(value: unknown, oid: string): VideoComment[] {
  if (value !== null && !Array.isArray(value) || Array.isArray(value) && value.length > 100) throw new Error('评论列表格式异常。')
  return (value as unknown[] | null ?? []).map(raw => parseVideoComment(raw, oid))
}
function paging(data: Record<string, unknown>, page: number) {
  const info = record(data.page)
  if (info?.num !== page || !positiveInteger(info.size) || info.size > 100 || typeof info.count !== 'number' || !Number.isSafeInteger(info.count) || info.count < 0) throw new Error('评论分页格式异常。')
  return { page, pageSize: info.size, total: info.count, hasMore: page * info.size < info.count }
}

export function createCommentsApi(json: (url: string) => Promise<unknown>, native: Pick<YuyinNativePlugin, 'mutateComment'>, generation: () => number): CommentsAPI {
  const requireRevision = (revision: number) => { if (revision !== generation()) throw new Error('账号已变化，请重新读取评论。') }
  async function videoOid(bvid: string) {
    validate(bvid)
    const view = apiData(await json(`${API}/x/web-interface/view?${new URLSearchParams({ bvid })}`))
    if (view.bvid !== bvid) throw new Error('视频信息与当前歌曲不一致。')
    return id(view.aid)
  }
  return {
    async list(bvid, sort, page) {
      validate(bvid, page)
      if (sort !== 'hot' && sort !== 'new') throw new Error('评论排序无效。')
      const revision = generation(), oid = await videoOid(bvid)
      const payload = await json(`${API}/x/v2/reply?${new URLSearchParams({ type: '1', oid, pn: String(page), ps: '20', sort: sort === 'hot' ? '2' : '0' })}`)
      requireRevision(revision)
      if (record(payload)?.code === 12002) return { bvid, oid, sort, page, pageSize: 20, total: 0, hasMore: false, closed: true, comments: [] }
      const data = apiData(payload)
      const result = comments(data.replies, oid)
      if (page === 1 && Array.isArray(data.top_replies)) result.unshift(...comments(data.top_replies, oid))
      const top = record(data.top)
      if (page === 1 && top) for (const pinned of Object.values(top)) if (record(pinned)) result.unshift(parseVideoComment(pinned, oid))
      const pageInfo = paging(data, page), closed = data.state === 2
      return { bvid, oid, sort, ...pageInfo, hasMore: !closed && pageInfo.hasMore, closed, comments: [...new Map(result.map(comment => [comment.id, comment])).values()] }
    },
    async replies(bvid, rootId, page) {
      validate(bvid, page)
      if (!validId(rootId)) throw new Error('评论标识无效。')
      const revision = generation(), oid = await videoOid(bvid)
      const data = apiData(await json(`${API}/x/v2/reply/reply?${new URLSearchParams({ type: '1', oid, root: rootId, pn: String(page), ps: '20' })}`))
      const root = parseVideoComment(data.root, oid)
      if (root.id !== rootId || root.rootId !== '0') throw new Error('回复所属评论不一致。')
      const result = comments(data.replies, oid)
      if (result.some(reply => reply.rootId !== rootId)) throw new Error('回复所属评论不一致。')
      requireRevision(revision)
      return { bvid, rootId, ...paging(data, page), comments: result }
    },
    async like(bvid, commentId, liked, expectedMid) {
      validate(bvid)
      if (!validId(commentId) || !positiveInteger(expectedMid) || typeof liked !== 'boolean') throw new Error('点赞参数无效。')
      const revision = generation()
      await native.mutateComment({ action: 'like', bvid, id: commentId, liked, expectedMid })
      requireRevision(revision)
      return { id: commentId, liked }
    },
    async create(request) {
      validate(request.bvid)
      if (!positiveInteger(request.expectedMid) || !request.message.trim() || request.message.trim().length > 1000 || request.rootId !== undefined && !validId(request.rootId) || request.parentId !== undefined && !validId(request.parentId)) throw new Error('评论参数无效。')
      const revision = generation()
      const response = await native.mutateComment({ ...request, message: request.message.trim(), action: 'create' })
      requireRevision(revision)
      const data = record(response.data)
      const comment = record(data?.reply) ? parseVideoComment(data!.reply, response.oid) : null
      return { comment, pendingReview: !comment }
    },
  }
}

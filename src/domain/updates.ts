export type UpdatePhase = 'idle' | 'checking' | 'upToDate' | 'available' | 'downloading' | 'ready' | 'permissionRequired' | 'installing' | 'error'
export interface MobileUpdate {
  version: string
  versionCode: number
  url: string
  size: number
  releaseNotesUrl: string
  notes: string
  publishedAt: string
}
export interface MobileUpdateState {
  state: UpdatePhase
  currentVersion: string
  currentVersionCode: number
  update?: MobileUpdate
  progress?: number
  message?: string
  code?: string
  revision?: number
}
const phases: UpdatePhase[] = ['idle', 'checking', 'upToDate', 'available', 'downloading', 'ready', 'permissionRequired', 'installing', 'error']
const versionPattern = /^(0|[1-9]\d{0,5})\.(0|[1-9]\d{0,5})\.(0|[1-9]\d{0,5})$/
const repo = 'https://github.com/panda472328/yuyin-music-mobile/releases'
export const updateCheckInterval = 6 * 60 * 60 * 1000
export function normalizeUpdateState(input: unknown): MobileUpdateState {
  const value = input as Partial<MobileUpdateState> | null
  if (!value || !phases.includes(value.state as UpdatePhase) || typeof value.currentVersion !== 'string' || !versionPattern.test(value.currentVersion)
    || !Number.isSafeInteger(value.currentVersionCode) || (value.currentVersionCode ?? 0) < 1) throw new Error('更新状态格式异常。请稍后重新检查。')
  const result: MobileUpdateState = { state: value.state as UpdatePhase, currentVersion: value.currentVersion, currentVersionCode: value.currentVersionCode! }
  if (value.update) {
    const update = value.update
    if (!versionPattern.test(update.version) || update.version.length > 32 || !Number.isSafeInteger(update.versionCode) || update.versionCode <= result.currentVersionCode
      || update.url !== `${repo}/download/android-v${update.version}/Yuyin-Mobile-${update.version}.apk`
      || update.releaseNotesUrl !== `${repo}/tag/android-v${update.version}` || !Number.isSafeInteger(update.size) || update.size < 1 || update.size > 200000000
      || typeof update.notes !== 'string' || update.notes.length > 8000 || typeof update.publishedAt !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{3})?Z$/.test(update.publishedAt)) throw new Error('更新信息格式异常。请稍后重新检查。')
    result.update = { ...update }
  }
  if (['available', 'downloading', 'ready', 'permissionRequired', 'installing'].includes(result.state) && !result.update) throw new Error('更新状态缺少版本信息。')
  if (value.progress !== undefined) {
    if (!Number.isFinite(value.progress) || value.progress < 0 || value.progress > 100) throw new Error('更新进度格式异常。')
    result.progress = value.progress
  }
  if (typeof value.message === 'string') result.message = value.message.slice(0, 500)
  if (typeof value.code === 'string') result.code = value.code.slice(0, 80)
  if (value.revision !== undefined) {
    if (!Number.isSafeInteger(value.revision) || value.revision < 0) throw new Error('更新状态序号异常。')
    result.revision = value.revision
  }
  return result
}
export function shouldCheckUpdate(lastCheck: number, now: number, state: UpdatePhase): boolean {
  return !['checking', 'downloading', 'ready', 'permissionRequired', 'installing'].includes(state) && (!lastCheck || now - lastCheck >= updateCheckInterval)
}

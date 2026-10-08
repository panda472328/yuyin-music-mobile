import { useCallback, useEffect, useRef, useState } from 'react'
import { Download, RefreshCw, X, LoaderCircle } from 'lucide-react'
import { mobile, isAndroid } from '../native/api'
import { shouldCheckUpdate, type MobileUpdateState } from '../domain/updates'
import packageInfo from '../../package.json'

const initial: MobileUpdateState = { state: 'idle', currentVersion: packageInfo.version, currentVersionCode: 1 }

export function useMobileUpdates() {
  const [state, setState] = useState(initial)
  const [dismissed, setDismissed] = useState<string | null>(null)
  const [pending, setPending] = useState(false)
  const pendingOperations = useRef(0)
  const current = useRef(state)
  const lastCheck = useRef(0)
  const alive = useRef(true)
  const receive = useCallback((value: MobileUpdateState) => {
    if (!alive.current) return
    if (value.revision !== undefined && current.current.revision !== undefined && value.revision < current.current.revision) return
    current.current = value
    setState(value)
  }, [])
  const invoke = useCallback(async (operation: () => Promise<MobileUpdateState>) => {
    pendingOperations.current++
    setPending(true)
    try { receive(await operation()) }
    catch (error) { receive({ ...current.current, state: 'error', message: error instanceof Error ? error.message : '更新失败，请稍后重试。' }) }
    finally { pendingOperations.current--; if (alive.current) setPending(pendingOperations.current > 0) }
  }, [receive])
  const check = useCallback(() => { lastCheck.current = Date.now(); setDismissed(null); return invoke(() => mobile.checkUpdate()) }, [invoke])
  useEffect(() => {
    alive.current = true
    if (!isAndroid) return () => { alive.current = false }
    let stopped = false
    const handle = mobile.addUpdateListener(value => { if (!stopped) receive(value) })
    void handle.catch(() => {})
    const automatic = () => {
      if (document.visibilityState === 'visible' && shouldCheckUpdate(lastCheck.current, Date.now(), current.current.state)) void check()
    }
    void mobile.getUpdateState().then(value => { if (!stopped) { receive(value); automatic() } }).catch(() => { if (!stopped) automatic() })
    document.addEventListener('visibilitychange', automatic)
    const timer = window.setInterval(automatic, 60 * 1000)
    return () => { stopped = true; alive.current = false; document.removeEventListener('visibilitychange', automatic); clearInterval(timer); void handle.then(listener => listener.remove()).catch(() => {}) }
  }, [check, receive])
  return { state, pending, check, download: () => invoke(() => mobile.downloadUpdate()), cancel: () => invoke(() => mobile.cancelUpdate()), install: () => invoke(() => mobile.installUpdate()), dismiss: () => setDismissed(state.update?.version ?? null), showBanner: !!state.update && dismissed !== state.update.version }
}

export type UpdateControls = ReturnType<typeof useMobileUpdates>
export function UpdateCard({ update, compact = false }: { update: UpdateControls; compact?: boolean }) {
  const { state, pending } = update
  const busy = state.state === 'checking' || state.state === 'downloading' || pending
  const installable = ['ready', 'permissionRequired', 'installing'].includes(state.state)
  const summary = state.state === 'checking' ? '正在检查更新…' : state.state === 'upToDate' ? '已经是最新版本' : state.state === 'downloading' ? `正在下载 ${Math.round(state.progress ?? 0)}%` : state.state === 'ready' ? '下载完成，可以安装' : state.state === 'permissionRequired' ? '请允许安装更新，再返回继续' : state.state === 'installing' ? '请在系统页面确认安装' : state.state === 'error' ? state.message : state.update ? `发现新版 ${state.update.version}` : '自动检查新版，由你选择更新'
  return <section className={`settings-card update-card ${compact ? 'update-banner' : ''}`} aria-label="软件更新">
    <div className="settings-label"><Download size={19} /><span><strong>{state.update ? `余音 ${state.update.version} 可更新` : '软件更新'}</strong><small>{summary}</small></span>{compact && <button className="icon-button" onClick={update.dismiss} aria-label="稍后更新"><X size={18} /></button>}</div>
    {!compact && <p className="update-version">当前版本 {state.currentVersion} · Android</p>}
    {!!state.update?.notes && <p className="update-notes">{state.update.notes}</p>}
    {state.state === 'downloading' && <progress max={100} value={state.progress ?? 0} aria-label="更新下载进度" />}
    {state.update && <p className="update-caption">安装需要系统确认，收藏、歌单和设置会保留。</p>}
    <div className="update-actions">
      {state.state === 'downloading' ? <button className="text-button" onClick={update.cancel}>取消下载</button> : installable ? <button className="primary-button" disabled={pending} onClick={update.install}>{pending && <LoaderCircle className="spin" size={16} />}继续安装</button> : state.update ? <button className="primary-button" disabled={busy} onClick={update.download}><Download size={16} />{state.state === 'error' ? '重新下载更新' : '下载更新'}</button> : null}
      {!compact && <button className="text-button" onClick={update.check} disabled={!isAndroid || busy || installable}>{state.state === 'checking' ? <LoaderCircle className="spin" size={16} /> : <RefreshCw size={16} />}检查更新</button>}
    </div>
    {!isAndroid && <p className="update-caption">请在 Android 安装版中检查更新。</p>}
  </section>
}

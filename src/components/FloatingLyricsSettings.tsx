import { useEffect, useRef, useState } from 'react'
import { AudioLines, LockKeyhole, UnlockKeyhole, RefreshCw } from 'lucide-react'
import { isAndroid, mobile } from '../native/api'
import type { FloatingLyricsSettings as Settings } from '../native/contract'
import LyricStylePicker from '../LyricStylePicker'

export default function FloatingLyricsSettings({ onError }: { onError: (error: string) => void }) {
  const [settings, setSettings] = useState<Settings | null>(null)
  const [busy, setBusy] = useState(false)
  const [fontSize, setFontSize] = useState(24)
  const [opacity, setOpacity] = useState(1)
  const ref = useRef<Settings | null>(null)
  const display = (next: Settings) => { ref.current = next; setSettings(next); setFontSize(next.fontSize); setOpacity(next.opacity) }
  const refresh = async () => { display(await mobile.getFloatingLyrics()) }
  useEffect(() => {
    let stopped = false
    const read = () => { if (document.visibilityState === 'visible') void mobile.getFloatingLyrics().then(value => { if (!stopped) display(value) }).catch(error => { if (!stopped) onError(error.message) }) }
    read(); document.addEventListener('visibilitychange', read)
    const listener = mobile.addStoreListener(key => { if (key === 'floating_lyrics') read() })
    void listener.catch(error => { if (!stopped) onError(error.message) })
    return () => { stopped = true; document.removeEventListener('visibilitychange', read); void listener.then(handle => handle.remove()).catch(() => {}) }
  }, [onError])
  const change = async (patch: Partial<Settings>) => {
    if (busy || !ref.current) return
    setBusy(true)
    try { display(await mobile.setFloatingLyrics({ ...ref.current, ...patch })) }
    catch (error) { if (ref.current) display(ref.current); onError(error instanceof Error ? error.message : '悬浮歌词设置失败。') }
    finally { setBusy(false) }
  }
  return <section className="settings-card floating-settings" aria-label="悬浮歌词设置">
    <div className="settings-label"><AudioLines size={19} /><span><strong>悬浮歌词</strong><small>切到其他应用后，跟随当前音源与歌词校准</small></span><button className="text-button" role="switch" aria-label="显示悬浮歌词" aria-checked={settings?.enabled ?? false} disabled={!isAndroid || busy || !settings} onClick={() => void change({ enabled: !settings?.enabled })}>{settings?.enabled ? '关闭' : '开启'}</button></div>
    {isAndroid && settings && !settings.permission && <div className="floating-permission"><p>首次开启需要允许“显示在其他应用上层”。返回后再点开启。</p><button className="text-button" disabled={busy} onClick={() => void mobile.requestOverlayPermission().then(refresh).catch(error => onError(error.message))}>前往授权</button><button className="icon-button" aria-label="重新检查悬浮窗权限" onClick={() => void refresh().catch(error => onError(error.message))}><RefreshCw size={17} /></button></div>}
    <LyricStylePicker label="悬浮歌词样式" desktop value={settings?.style ?? 'classic'} disabled={!isAndroid || busy || !settings} onChange={style => void change({ style })} />
    {settings?.style === 'minecraft' && <p className="update-caption">我的世界样式使用内置像素字体，切换其他样式后恢复原字体。</p>}
    <div className="settings-fields">
      <label>字体<select aria-label="悬浮歌词字体" disabled={!isAndroid || busy || !settings || settings.style === 'minecraft'} value={settings?.font ?? 'sans'} onChange={event => void change({ font: event.target.value as Settings['font'] })}><option value="sans">简洁黑体</option><option value="serif">典雅衬线</option><option value="kai">手写风格</option><option value="rounded">圆润字体</option></select></label>
      <label>颜色<select aria-label="悬浮歌词颜色" disabled={!isAndroid || busy || !settings} value={settings?.color ?? 'white'} onChange={event => void change({ color: event.target.value as Settings['color'] })}><option value="white">白色</option><option value="mint">薄荷绿</option><option value="gold">暖金色</option></select></label>
      <label>字号 <output>{fontSize}sp</output><input type="range" aria-label="悬浮歌词字号" min={16} max={40} step={2} disabled={!isAndroid || busy || !settings} value={fontSize} onChange={event => setFontSize(Number(event.target.value))} onPointerUp={event => void change({ fontSize: Number(event.currentTarget.value) })} onPointerCancel={() => setFontSize(ref.current?.fontSize ?? 24)} onKeyUp={event => { if (event.key.startsWith('Arrow') || ['Home', 'End'].includes(event.key)) void change({ fontSize: Number(event.currentTarget.value) }) }} /></label>
      <label>透明度 <output>{Math.round(opacity * 100)}%</output><input type="range" aria-label="悬浮歌词透明度" min={.3} max={1} step={.1} disabled={!isAndroid || busy || !settings} value={opacity} onChange={event => setOpacity(Number(event.target.value))} onPointerUp={event => void change({ opacity: Number(event.currentTarget.value) })} onPointerCancel={() => setOpacity(ref.current?.opacity ?? 1)} onKeyUp={event => { if (event.key.startsWith('Arrow') || ['Home', 'End'].includes(event.key)) void change({ opacity: Number(event.currentTarget.value) }) }} /></label>
    </div>
    <div className="setting-inline-actions"><button className="text-button" disabled={!isAndroid || busy || !settings} onClick={() => void change({ locked: !settings?.locked })}>{settings?.locked ? <UnlockKeyhole size={17} /> : <LockKeyhole size={17} />}{settings?.locked ? '解锁位置' : '锁定位置'}</button><button className="text-button" disabled={!isAndroid || busy || !settings} onClick={() => void change({ x: .5, y: .2 })}>重置位置</button></div>
    <p className="update-caption">拖动文字或空白移动；轻触显示工具栏。锁定后只显示中央解锁图标，文字区域不拦截触摸。关闭播放服务后浮窗收起，下次播放恢复。</p>
  </section>
}

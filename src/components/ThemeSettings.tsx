import { Check, Settings2 } from 'lucide-react'
import type { MobilePreferences } from '../native/contract'

const options = [
  { id: 'classic', label: '经典 · 草木', detail: '温润留白，安静听歌' },
  { id: 'bilibili', label: 'Bilibili', detail: '轻盈粉白，清爽蓝调' },
  { id: 'system', label: '跟随系统', detail: '随手机自动切换明暗' },
  { id: 'pixel', label: '我的世界', detail: '像素字、物品栏与经验条' },
] as const

export default function ThemeSettings({ value, disabled, onChange }: {
  value: NonNullable<MobilePreferences['theme']>; disabled: boolean; onChange: (theme: NonNullable<MobilePreferences['theme']>) => void
}) {
  return <section className="settings-card theme-settings" aria-label="界面主题">
    <div className="settings-label"><Settings2 size={19} /><span><strong>界面主题</strong><small>给音乐换一种心情，选择会保存在这台手机上</small></span></div>
    <div className="theme-options" role="group" aria-label="选择界面主题">{options.map(theme => <button type="button" key={theme.id} className={`theme-option ${value === theme.id ? 'selected' : ''}`} aria-pressed={value === theme.id} disabled={disabled} onClick={() => onChange(theme.id)}>
      <span className="theme-option-preview" data-preview={theme.id} aria-hidden="true"><span className="theme-option-preview-sidebar" /><span className="theme-option-preview-content"><i /><i /><i /></span></span>
      <span className="theme-option-details"><strong>{theme.label}</strong><small>{theme.detail}</small></span><Check size={16} aria-hidden="true" className="theme-option-check" />
    </button>)}</div>
  </section>
}

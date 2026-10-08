import type { PluginListenerHandle } from '@capacitor/core'
import type { Song, PlaybackStatus } from '../types'
import type { MobileUpdateState } from '../domain/updates'
export interface YuyinNativePlugin {
  request(options: { url: string }): Promise<{ status: number; body: string }>
  openLogin(): Promise<void>
  openSource(): Promise<void>
  play(options: { song: Song }): Promise<PlaybackStatus>
  pause(): Promise<PlaybackStatus>
  resume(): Promise<PlaybackStatus>
  seek(options: { seconds: number }): Promise<PlaybackStatus>
  setVolume(options: { volume: number }): Promise<PlaybackStatus>
  getStatus(): Promise<PlaybackStatus>
  checkUpdate(): Promise<MobileUpdateState>
  downloadUpdate(): Promise<MobileUpdateState>
  cancelUpdate(): Promise<MobileUpdateState>
  installUpdate(): Promise<MobileUpdateState>
  getUpdateState(): Promise<MobileUpdateState>
  readStore(options: { key: 'library' | 'preferences' }): Promise<{ data: string | null }>
  writeStore(options: { key: 'library' | 'preferences'; data: string }): Promise<{ saved: boolean }>
  addListener(event: 'status', callback: (status: PlaybackStatus) => void): Promise<PluginListenerHandle>
  addListener(event: 'ended', callback: (event: { song: Song }) => void): Promise<PluginListenerHandle>
  addListener(event: 'sessionChanged', callback: () => void): Promise<PluginListenerHandle>
  addListener(event: 'updateState', callback: (state: MobileUpdateState) => void): Promise<PluginListenerHandle>
}
export interface MobilePreferences {
  version: 1
  lyricsProvider: 'bilibili' | 'lrclib'
  lyricOffsets: Record<string, number>
}

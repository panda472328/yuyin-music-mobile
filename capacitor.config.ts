import type { CapacitorConfig } from '@capacitor/cli'
const config: CapacitorConfig = {
  appId: 'com.yuyin.music.mobile',
  appName: '余音手机版',
  webDir: 'dist',
  android: { backgroundColor: '#f7f8f3', allowMixedContent: false, minWebViewVersion: 91 },
  server: { androidScheme: 'https', hostname: 'localhost' }
}
export default config

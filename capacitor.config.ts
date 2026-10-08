import type { CapacitorConfig } from '@capacitor/cli'
const config: CapacitorConfig = {
  appId: 'com.yuyin.music.mobile',
  appName: '余音手机版',
  webDir: 'dist',
  android: { backgroundColor: '#11151a', allowMixedContent: false, minWebViewVersion: 91 },
  server: { androidScheme: 'https', hostname: 'localhost' }
}
export default config

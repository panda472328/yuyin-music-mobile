import assert from 'node:assert/strict'
import fs from 'node:fs'
import vm from 'node:vm'
import test from 'node:test'

const source = fs.readFileSync(new URL('../android/app/src/main/java/com/yuyin/music/mobile/MediaTimeline.java', import.meta.url), 'utf8')
function script(method: string) {
  const body = source.split(`static String ${method}() {`)[1].split('\n    }')[0]
  return [...body.matchAll(/"(?:[^"\\]|\\.)*"/g)].map(match => JSON.parse(match[0])).join('')
}
function timeline() {
  let wall = 100
  const listeners: Record<string, (event: { isTrusted: boolean }) => void> = {}
  const m: any = {}
  const v = { currentTime: 90, paused: false, readyState: 4, playbackRate: 1 }
  const context = vm.createContext({ m, v, a: { command: 'read', value: 0 }, performance: { now: () => wall }, document: { addEventListener: (event: string, listener: any) => { listeners[event] = listener } } })
  vm.runInContext(script('install'), context)
  function tick(seconds: number, position: number, command = 'read') {
    wall += seconds * 1000; v.currentTime = position; context.a = { command, value: position }
    vm.runInContext(`(function(){${script('guard')}})()`, context)
    return v.currentTime
  }
  return { v, m, tick, listeners }
}
test('new selections force the first source position to zero, even with an unseen source restore', () => {
  const t = timeline()
  assert.equal(t.tick(0, 90), 0)
  assert.equal(t.tick(.5, .5), .5)
})
test('whole-track progress protection rejects late jumps and keeps pause/resume positions', () => {
  const t = timeline(); t.tick(0, 0)
  assert.equal(t.tick(15, 15), 15)
  assert.equal(t.tick(.5, 90), 15)
  t.v.paused = true; t.tick(.5, 15)
  assert.equal(t.tick(20, 95), 15)
  t.v.paused = false; t.tick(.1, 15)
  assert.equal(t.tick(1, 16), 16)
})
test('native seeks and trusted source gestures can take control, synthetic gestures cannot', () => {
  const t = timeline(); t.tick(0, 0)
  assert.equal(t.tick(.1, 70, 'seek'), 70)
  t.listeners.pointerdown({ isTrusted: false })
  assert.equal(t.tick(.1, 130), 70)
  t.listeners.pointerdown({ isTrusted: true })
  assert.equal(t.tick(.1, 130), 130)
})
test('background polling gaps and playback speed allow natural progress without an expired guard', () => {
  const t = timeline(); t.tick(0, 0); t.v.playbackRate = 2
  assert.equal(t.tick(25, 50), 50)
  assert.equal(t.tick(.5, 200), 50)
  assert.equal(t.tick(.5, 5), 50)
})

import assert from 'node:assert/strict'
import test from 'node:test'
import { AccountGateController, type AccountGateState } from '../src/domain/account-gate'
import type { BilibiliAccountStatus } from '../src/types'

const anonymous: BilibiliAccountStatus = { loggedIn: false, account: null }
const authenticated: BilibiliAccountStatus = { loggedIn: true, account: { mid: 99, username: '虚构账号', avatar: '' } }
function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (error: Error) => void
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}

test('a delayed logged-out response cannot overwrite a requested post-login verification', async () => {
  const first = deferred<BilibiliAccountStatus>()
  const states: AccountGateState[] = []
  let requests = 0
  const gate = new AccountGateController(() => ++requests === 1 ? first.promise : Promise.resolve(authenticated), state => states.push(state))
  const start = gate.refresh()
  const login = gate.refresh()
  first.resolve(anonymous)
  await Promise.all([start, login])
  assert.equal(requests, 2)
  assert.deepEqual(states.at(-1), { status: authenticated, checking: false, error: null })
  assert.equal(states.some(state => !state.checking && !state.status.loggedIn), false)
})

test('multiple cookie events during a request coalesce into one fresh official check', async () => {
  const first = deferred<BilibiliAccountStatus>()
  let requests = 0
  const gate = new AccountGateController(() => ++requests === 1 ? first.promise : Promise.resolve(authenticated), () => {})
  const initial = gate.refresh()
  for (let i = 0; i < 8; i++) void gate.refresh()
  first.resolve(anonymous)
  await initial
  assert.equal(requests, 2)
})

test('an obsolete session-change rejection does not leave the successful login gate in error', async () => {
  const old = deferred<BilibiliAccountStatus>()
  const states: AccountGateState[] = []
  let calls = 0
  const gate = new AccountGateController(() => ++calls === 1 ? old.promise : Promise.resolve(authenticated), state => states.push(state))
  const pending = gate.refresh()
  void gate.refresh()
  old.reject(new Error('Bilibili 账号已发生变化'))
  await pending
  assert.equal(states.some(state => state.error !== null), false)
  assert.equal(states.at(-1)?.status.loggedIn, true)
})

test('network failure keeps the last verified identity and confirmed logout closes the gate', async () => {
  const states: AccountGateState[] = []
  let next: () => Promise<BilibiliAccountStatus> = () => Promise.resolve(authenticated)
  const gate = new AccountGateController(() => next(), state => states.push(state))
  await gate.refresh()
  next = () => Promise.reject(new Error('网络连接失败'))
  await gate.refresh()
  assert.equal(states.at(-1)?.status.loggedIn, true)
  assert.equal(states.at(-1)?.error, '网络连接失败')
  next = () => Promise.resolve(anonymous)
  await gate.refresh()
  assert.deepEqual(states.at(-1), { status: anonymous, checking: false, error: null })
})

test('an unmounted gate cannot publish a late response into the remounted application', async () => {
  const old = deferred<BilibiliAccountStatus>()
  const states: AccountGateState[] = []
  const gate = new AccountGateController(() => old.promise, state => states.push(state))
  const pending = gate.refresh()
  gate.dispose()
  const publications = states.length
  const freshStates: AccountGateState[] = []
  const fresh = new AccountGateController(() => Promise.resolve(authenticated), state => freshStates.push(state))
  await fresh.refresh()
  old.resolve(anonymous)
  await pending
  await gate.refresh()
  assert.equal(states.length, publications)
  assert.equal(freshStates.at(-1)?.status.loggedIn, true)
})

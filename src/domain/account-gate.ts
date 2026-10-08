import type { BilibiliAccountStatus } from '../types'

export interface AccountGateState {
  status: BilibiliAccountStatus
  checking: boolean
  error: string | null
}

/** Coalesces session events and prevents an older account response reopening the login gate. */
export class AccountGateController {
  private state: AccountGateState = { status: { loggedIn: false, account: null }, checking: true, error: null }
  private revision = 0
  private pending: Promise<void> | null = null
  private disposed = false

  constructor(
    private readonly fetchStatus: () => Promise<BilibiliAccountStatus>,
    private readonly publish: (state: AccountGateState) => void,
  ) {}

  refresh(): Promise<void> {
    if (this.disposed) return Promise.resolve()
    this.revision++
    this.update({ ...this.state, checking: true, error: null })
    if (!this.pending) this.pending = this.checkUntilCurrent().finally(() => { this.pending = null })
    return this.pending
  }

  dispose(): void { this.disposed = true; this.revision++ }

  private update(state: AccountGateState): void {
    if (!this.disposed) { this.state = state; this.publish(state) }
  }

  private async checkUntilCurrent(): Promise<void> {
    while (!this.disposed) {
      const revision = this.revision
      try {
        const status = await this.fetchStatus()
        if (this.disposed) return
        if (revision !== this.revision) continue
        this.update({ status, checking: false, error: null })
      } catch (failure) {
        if (this.disposed) return
        if (revision !== this.revision) continue
        this.update({ ...this.state, checking: false, error: failure instanceof Error ? failure.message : '登录状态验证失败，请重试。' })
      }
      return
    }
  }
}

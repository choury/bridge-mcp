/**
 * 插件入口：连 bridge、执行能力、给设置页提供 RPC。
 */
import { BridgeLink } from './bridge.js'
import { Store } from './config.js'
import { registerRpc } from './rpc.js'

export const name = 'dsh-bridge-mcp'
export const inject = ['connection']

export function apply(ctx) {
  const store = new Store()
  const link = new BridgeLink({
    getConfig: () => store.config,
    log: (level, message) => store.log(level, message),
    onOnline: () => {
      if (!store.config.enabled) store.save({ enabled: true })
    },
  })

  ctx.effect(() => registerRpc(ctx, store, link), 'dsh-bridge-mcp: rpc')

  ctx.effect(() => {
    if (store.config.enabled) link.connect()
    return () => link.dispose()
  }, 'dsh-bridge-mcp: link')
}

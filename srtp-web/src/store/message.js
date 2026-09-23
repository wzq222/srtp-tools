import { reactive } from 'vue'

let seq = 0
export const messages = reactive([])

export function notify(message, type = 'info', timeout = 2500) {
  const id = ++seq
  messages.push({ id, message, type })
  setTimeout(() => {
    const idx = messages.findIndex((m) => m.id === id)
    if (idx >= 0) messages.splice(idx, 1)
  }, timeout)
}

export function confirmDialog(message) {
  return window.confirm(message)
}

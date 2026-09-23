import { reactive } from 'vue'
import { api } from '../api'

export const session = reactive({
  loggedIn: false,
  username: '',
  userId: null
})

export async function checkSession() {
  try {
    const r = await api.getSession()
    if (r.data && r.data.ok) {
      session.loggedIn = true
      session.username = r.data.username
      session.userId = r.data.userId
    } else {
      session.loggedIn = false
    }
  } catch (e) {
    session.loggedIn = false
  }
  return session.loggedIn
}

export async function login(username, password) {
  const r = await api.login(username, password)
  if (r.data && r.data.ok) {
    session.loggedIn = true
    session.username = r.data.username
    session.userId = r.data.userId
    return { ok: true }
  }
  return { ok: false, error: (r.data && r.data.error) || '登录失败' }
}

export async function register(username, password) {
  const r = await api.register(username, password)
  if (r.data && r.data.ok) return { ok: true }
  return { ok: false, error: (r.data && r.data.error) || '注册失败' }
}

export async function logout() {
  await api.logout()
  session.loggedIn = false
  session.username = ''
  session.userId = null
}

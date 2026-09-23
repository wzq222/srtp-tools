<template>
  <div class="login-page">
    <div class="login-box">
      <h2>SRTP TSP 算法平台</h2>

      <!-- 登录 -->
      <div v-if="mode === 'login'">
        <div class="error-msg">{{ loginError }}</div>
        <div class="field">
          <label>用户名</label>
          <input v-model="loginForm.username" type="text" autocomplete="username" @keyup.enter="doLogin" />
        </div>
        <div class="field">
          <label>密码</label>
          <input v-model="loginForm.password" type="password" autocomplete="current-password" @keyup.enter="doLogin" />
        </div>
        <button class="btn primary" style="width:100%;height:40px;font-size:15px" :disabled="busy" @click="doLogin">登录</button>
        <div style="text-align:center;margin-top:14px">
          <span class="text-link" @click="switchMode('register')">没有账号？立即注册</span>
        </div>
      </div>

      <!-- 注册 -->
      <div v-else>
        <div class="error-msg">{{ regError }}</div>
        <div class="field">
          <label>用户名</label>
          <input v-model="regForm.username" type="text" autocomplete="off" @keyup.enter="doRegister" />
        </div>
        <div class="field">
          <label>密码（至少 4 位）</label>
          <input v-model="regForm.password" type="password" autocomplete="new-password" @keyup.enter="doRegister" />
        </div>
        <div class="field">
          <label>确认密码</label>
          <input v-model="regForm.password2" type="password" autocomplete="new-password" @keyup.enter="doRegister" />
        </div>
        <button class="btn primary" style="width:100%;height:40px;font-size:15px" :disabled="busy" @click="doRegister">注册</button>
        <div style="text-align:center;margin-top:14px">
          <span class="text-link" @click="switchMode('login')">已有账号？返回登录</span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { login, register } from '../store/session'
import { notify } from '../store/message'

const mode = ref('login')
const busy = ref(false)
const loginError = ref('')
const regError = ref('')

const loginForm = reactive({ username: '', password: '' })
const regForm = reactive({ username: '', password: '', password2: '' })

function switchMode(m) {
  mode.value = m
  loginError.value = ''
  regError.value = ''
}

async function doLogin() {
  loginError.value = ''
  if (!loginForm.username) { loginError.value = '请输入用户名'; return }
  if (!loginForm.password) { loginError.value = '请输入密码'; return }
  busy.value = true
  const r = await login(loginForm.username, loginForm.password)
  busy.value = false
  if (!r.ok) loginError.value = r.error
}

async function doRegister() {
  regError.value = ''
  if (!regForm.username) { regError.value = '请输入用户名'; return }
  if (!regForm.password) { regError.value = '请输入密码'; return }
  if (!regForm.password2) { regError.value = '请确认密码'; return }
  if (regForm.password !== regForm.password2) { regError.value = '两次密码不一致'; return }
  busy.value = true
  const r = await register(regForm.username, regForm.password)
  busy.value = false
  if (r.ok) {
    notify('注册成功，请登录', 'success')
    switchMode('login')
    regForm.username = ''
    regForm.password = ''
    regForm.password2 = ''
  } else {
    regError.value = r.error
  }
}
</script>

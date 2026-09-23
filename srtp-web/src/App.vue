<template>
  <LoginView v-if="!session.loggedIn" />
  <MainView v-else />

  <!-- 全局提示消息 -->
  <div class="toast-wrap">
    <div v-for="m in messages" :key="m.id" class="toast" :class="m.type">{{ m.message }}</div>
  </div>
</template>

<script setup>
import { onMounted } from 'vue'
import LoginView from './views/LoginView.vue'
import MainView from './views/MainView.vue'
import { session, checkSession } from './store/session'
import { messages } from './store/message'

onMounted(() => { checkSession() })
</script>

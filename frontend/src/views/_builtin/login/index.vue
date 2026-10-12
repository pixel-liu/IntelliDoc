<script setup lang="ts">
import { computed } from 'vue';
import type { Component } from 'vue';
import { mixColor } from '@sa/color';
import { loginModuleRecord } from '@/constants/app';
import { useAppStore } from '@/store/modules/app';
import { useThemeStore } from '@/store/modules/theme';
import { $t } from '@/locales';
import PwdLogin from './modules/pwd-login.vue';
import CodeLogin from './modules/code-login.vue';
import Register from './modules/register.vue';
import ResetPwd from './modules/reset-pwd.vue';
import BindWechat from './modules/bind-wechat.vue';

interface Props {
  /** The login module */
  module?: UnionKey.LoginModule;
}

const props = defineProps<Props>();

const appStore = useAppStore();
const themeStore = useThemeStore();

interface LoginModule {
  label: string;
  component: Component;
}

const moduleMap: Record<UnionKey.LoginModule, LoginModule> = {
  'pwd-login': { label: loginModuleRecord['pwd-login'], component: PwdLogin },
  'code-login': { label: loginModuleRecord['code-login'], component: CodeLogin },
  register: { label: loginModuleRecord.register, component: Register },
  'reset-pwd': { label: loginModuleRecord['reset-pwd'], component: ResetPwd },
  'bind-wechat': { label: loginModuleRecord['bind-wechat'], component: BindWechat }
};

const activeModule = computed(() => moduleMap[props.module || 'pwd-login']);
const isRegisterModule = computed(() => (props.module || 'pwd-login') === 'register');

const bgColor = computed(() => {
  const ratio = themeStore.darkMode ? 0.9 : 0;

  return mixColor('#fff', '#000', ratio);
});
</script>

<template>
  <div class="login-page" :style="{ backgroundColor: bgColor }">
    <section class="login-story" aria-label="IntelliDoc 产品介绍">
      <div class="login-story__brand">
        <span class="login-story__mark"><SystemLogo /></span>
        <span>{{ $t('system.title') }}</span>
      </div>
      <div class="login-story__content">
        <p class="login-story__kicker">企业知识工作台</p>
        <h1>让知识可追溯，<br />让答案有依据。</h1>
        <p class="login-story__lead">
          统一管理文档、检索证据与智能对话，把散落的信息整理成团队可信赖的知识脉络。
        </p>
        <ul class="login-story__features">
          <li><span></span>文档入库与向量化进度清晰可见</li>
          <li><span></span>每次回答保留来源与页码证据</li>
          <li><span></span>组织与用户权限贯穿检索全程</li>
        </ul>
      </div>
      <p class="login-story__footer">IntelliDoc · 知识中枢</p>
    </section>

    <section class="login-workspace" :class="{ 'login-workspace--wide': isRegisterModule }">
      <div class="login-workspace__tools">
        <ThemeSchemaSwitch
          :theme-schema="themeStore.themeScheme"
          :show-tooltip="false"
          class="text-20px"
          @switch="themeStore.toggleThemeScheme"
        />
        <LangSwitch
          v-if="themeStore.header.multilingual.visible"
          :lang="appStore.locale"
          :lang-options="appStore.localeOptions"
          :show-tooltip="false"
          @change-lang="appStore.changeLocale"
        />
      </div>

      <NCard :bordered="false" class="login-card">
        <div :class="isRegisterModule ? 'login-panel login-panel--register' : 'login-panel'">
          <header class="login-panel__header">
            <span class="login-panel__index">{{ isRegisterModule ? '创建工作区账户' : '返回你的知识空间' }}</span>
            <h2>{{ $t(activeModule.label) }}</h2>
            <p>{{ isRegisterModule ? '填写账户信息，开始建立团队知识库。' : '继续管理文档、证据与智能对话。' }}</p>
          </header>
          <main class="login-panel__body">
            <Transition :name="themeStore.page.animateMode" mode="out-in" appear>
              <component :is="activeModule.component" />
            </Transition>
          </main>
        </div>
      </NCard>
    </section>
  </div>
</template>

<style scoped>
.login-page {
  display: grid;
  min-height: 100%;
  grid-template-columns: minmax(320px, 0.92fr) minmax(520px, 1.08fr);
  color: var(--id-ink);
  overflow-x: hidden;
  background: var(--id-canvas) !important;
}

.login-story {
  position: relative;
  display: flex;
  overflow: hidden;
  min-height: 100vh;
  flex-direction: column;
  justify-content: space-between;
  border-right: 1px solid rgba(255, 255, 255, 0.09);
  color: #eaf2ef;
  background: #152b33;
  padding: clamp(30px, 5vw, 72px);
}

.login-story::before,
.login-story::after {
  position: absolute;
  right: 12%;
  border: 1px solid rgba(119, 205, 193, 0.17);
  content: '';
}

.login-story::before {
  top: 17%;
  width: 42%;
  height: 31%;
  border-radius: 3px 26px 3px 3px;
}

.login-story::after {
  top: 54%;
  width: 28%;
  height: 18%;
  border-color: rgba(196, 119, 50, 0.24);
  border-radius: 3px;
}

.login-story__brand {
  z-index: 1;
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 18px;
  font-weight: 680;
  letter-spacing: -0.02em;
}

.login-story__mark {
  display: inline-flex;
  width: 40px;
  height: 40px;
  align-items: center;
  justify-content: center;
  border: 1px solid #58b8ae;
  border-radius: 9px 9px 9px 2px;
  color: #79c9c0;
  font-size: 24px;
}

.login-story__content {
  z-index: 1;
  max-width: 520px;
}

.login-story__kicker {
  margin: 0 0 18px;
  color: #79c9c0;
  font-size: 13px;
  font-weight: 600;
  letter-spacing: 0.12em;
}

.login-story h1 {
  margin: 0;
  font-size: clamp(38px, 4.3vw, 66px);
  font-weight: 620;
  letter-spacing: -0.055em;
  line-height: 1.12;
}

.login-story__lead {
  max-width: 34em;
  margin: 28px 0 0;
  color: rgba(234, 242, 239, 0.7);
  font-size: 16px;
  line-height: 1.8;
}

.login-story__features {
  display: grid;
  margin: 34px 0 0;
  gap: 13px;
  padding: 0;
  color: rgba(234, 242, 239, 0.84);
  list-style: none;
}

.login-story__features li {
  display: flex;
  align-items: center;
  gap: 11px;
}

.login-story__features span {
  width: 18px;
  height: 1px;
  flex: 0 0 18px;
  background: #58b8ae;
}

.login-story__footer {
  z-index: 1;
  margin: 0;
  color: rgba(234, 242, 239, 0.42);
  font-size: 12px;
}

.login-workspace {
  position: relative;
  display: flex;
  min-width: 0;
  align-items: center;
  justify-content: center;
  box-sizing: border-box;
  width: 100%;
  max-width: 100vw;
  padding: 72px clamp(28px, 6vw, 96px);
}

.login-workspace--wide {
  padding-inline: clamp(24px, 3vw, 52px);
}

.login-workspace__tools {
  position: absolute;
  top: 24px;
  right: 28px;
  display: flex;
  gap: 8px;
}

.login-card {
  width: min(100%, 440px);
  min-width: 0;
  border: 0;
  background: transparent;
  box-shadow: none;
}

.login-workspace--wide .login-card {
  width: min(100%, 860px);
}

.login-card :deep(.n-card__content) {
  padding: 0;
}

.login-panel {
  width: 100%;
}

.login-panel__header {
  margin-bottom: 30px;
}

.login-panel__index {
  display: block;
  margin-bottom: 12px;
  color: var(--id-primary);
  font-size: 12px;
  font-weight: 650;
  letter-spacing: 0.08em;
}

.login-panel__header h2 {
  margin: 0;
  color: var(--id-ink);
  font-size: 30px;
  font-weight: 680;
  letter-spacing: -0.04em;
}

.login-panel__header p {
  margin: 10px 0 0;
  color: var(--id-ink-soft);
  line-height: 1.7;
}

.login-panel__body {
  border-top: 1px solid var(--id-line);
  padding-top: 28px;
}

@media (max-width: 900px) {
  .login-page {
    display: block;
  }

  .login-story {
    min-height: auto;
    padding: 26px 28px;
  }

  .login-story__content,
  .login-story__footer {
    display: none;
  }

  .login-workspace {
    min-height: calc(100vh - 93px);
    padding: 64px 24px 36px;
  }
}

@media (max-width: 640px) {
  .login-workspace {
    overflow: hidden;
    padding-inline: 28px;
  }

  .login-workspace__tools {
    top: 18px;
    right: 18px;
  }

  .login-card {
    width: calc(100vw - 56px);
    max-width: calc(100vw - 56px);
    flex: 0 0 calc(100vw - 56px);
  }

  .login-panel,
  .login-panel__body {
    width: 100%;
    min-width: 0;
    max-width: 100%;
  }

  .login-card :deep(.n-form) {
    min-width: 0;
  }

  .login-panel__header h2 {
    font-size: 26px;
  }
}
</style>

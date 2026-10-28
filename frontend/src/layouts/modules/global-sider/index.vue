<script setup lang="ts">
import { computed } from 'vue';
import { GLOBAL_SIDER_MENU_ID } from '@/constants/app';
import { useAppStore } from '@/store/modules/app';
import { useThemeStore } from '@/store/modules/theme';
import GlobalLogo from '../global-logo/index.vue';

defineOptions({
  name: 'GlobalSider'
});

const appStore = useAppStore();
const themeStore = useThemeStore();

const isVerticalMix = computed(() => themeStore.layout.mode === 'vertical-mix');
const isHorizontalMix = computed(() => themeStore.layout.mode === 'horizontal-mix');
const darkMenu = computed(() => !themeStore.darkMode && !isHorizontalMix.value && themeStore.sider.inverted);
const showLogo = computed(() => !isVerticalMix.value && !isHorizontalMix.value);
const menuWrapperClass = computed(() => (showLogo.value ? 'flex-1-hidden' : 'h-full'));
</script>

<template>
  <DarkModeContainer class="app-sider size-full flex-col-stretch shadow-sider" :inverted="darkMenu">
    <GlobalLogo
      v-if="showLogo"
      :show-title="!appStore.siderCollapse"
      :style="{ height: themeStore.header.height + 'px' }"
    />
    <div :id="GLOBAL_SIDER_MENU_ID" :class="menuWrapperClass" class="app-sider__menu"></div>
  </DarkModeContainer>
</template>

<style scoped>
.app-sider {
  position: relative;
  border-right: 1px solid var(--id-line);
  background: var(--id-paper);
}

.app-sider__menu {
  padding: 10px 8px 48px;
}

:deep(.n-menu-item) {
  margin: 2px 0;
}

:deep(.n-menu-item-content) {
  min-height: 38px;
  border-radius: 5px;
}

:deep(.n-menu-item-content::before) {
  border-radius: 5px !important;
}

:deep(.n-menu-item-content-header) {
  font-size: 13px;
  font-weight: 500;
}

:deep(.n-menu-item-content--selected) {
  position: relative;
}

:deep(.n-menu-item-content--selected)::after {
  position: absolute;
  left: -8px;
  width: 3px;
  height: 20px;
  border-radius: 0 2px 2px 0;
  background: var(--id-primary);
  content: '';
}
</style>

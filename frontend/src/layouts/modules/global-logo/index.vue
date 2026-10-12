<script setup lang="ts">
import { $t } from '@/locales';

defineOptions({
  name: 'GlobalLogo'
});

interface Props {
  /** Whether to show the title */
  showTitle?: boolean;
}

withDefaults(defineProps<Props>(), {
  showTitle: true
});
</script>

<template>
  <RouterLink to="/" class="app-brand w-full nowrap-hidden">
    <span class="app-brand__mark">
      <SystemLogo class="text-24px" />
    </span>
    <span v-show="showTitle" class="app-brand__text">
      <strong>{{ $t('system.title') }}</strong>
      <small>知识中枢</small>
    </span>
  </RouterLink>
</template>

<style scoped>
.app-brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 13px;
  color: var(--id-ink);
}

.app-brand__mark {
  display: inline-flex;
  width: 38px;
  height: 38px;
  flex: 0 0 38px;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--id-primary);
  border-radius: 8px 8px 8px 2px;
  color: var(--id-primary);
  background: var(--id-primary-quiet);
}

.app-brand__text {
  display: flex;
  min-width: 0;
  flex-direction: column;
  line-height: 1.1;
}

.app-brand__text strong {
  font-size: 16px;
  font-weight: 720;
  letter-spacing: -0.02em;
}

.app-brand__text small {
  margin-top: 5px;
  color: var(--id-ink-soft);
  font-size: 10px;
  letter-spacing: 0.14em;
}
</style>

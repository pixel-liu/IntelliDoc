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
  gap: 9px;
  padding: 0 12px;
  color: var(--id-ink);
}

.app-brand__mark {
  display: inline-flex;
  width: 32px;
  height: 32px;
  flex: 0 0 32px;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--id-primary);
  border-radius: 6px 6px 6px 2px;
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
  font-size: 15px;
  font-weight: 680;
  letter-spacing: -0.02em;
}

.app-brand__text small {
  margin-top: 3px;
  color: var(--id-ink-soft);
  font-size: 10px;
  letter-spacing: 0.1em;
}
</style>

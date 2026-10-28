<script lang="ts" setup>
import logo from '@/assets/svg-icon/logo.svg?raw';

defineOptions({ name: 'SystemLogo' });
</script>

<template>
  <span class="system-logo" aria-hidden="true" v-html="logo" />
</template>

<style scoped>
.system-logo {
  display: inline-flex;
  align-items: center;
  justify-content: center;
}

.system-logo :deep(svg) {
  display: block;
  width: 1em;
  height: 1em;
}
</style>

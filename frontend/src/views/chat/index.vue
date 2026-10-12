<script setup lang="ts">
import { computed, ref } from 'vue';
import { useRoute } from 'vue-router';
import ChatList from './modules/chat-list.vue';
import InputBox from './modules/input-box.vue';
import ConversationSidebar from './modules/conversation-sidebar.vue';
import ReferencePreviewPage from './modules/reference-preview-page.vue';

const route = useRoute();
const showReferencePreview = computed(() => route.query.preview === 'reference');
const sidebarCollapsed = ref(false);
</script>

<template>
  <div v-if="showReferencePreview" class="h-full">
    <ReferencePreviewPage />
  </div>
  <div v-else class="chat-page">
    <div class="chat-workbench">
      <ConversationSidebar v-model:collapsed="sidebarCollapsed" />
      <div class="chat-main">
        <button
          v-show="sidebarCollapsed"
          class="chat-sidebar-trigger"
          aria-label="展开对话列表"
          @click="sidebarCollapsed = false"
        >
          <icon-material-symbols:left-panel-open-outline-rounded class="text-18px" />
        </button>
        <ChatList />
        <InputBox />
      </div>
    </div>
  </div>
</template>

<style scoped>
.chat-page {
  height: 100%;
  padding: 14px 18px 20px;
  background: var(--id-canvas);
}

.chat-workbench {
  position: relative;
  display: flex;
  overflow: hidden;
  height: 100%;
  border: 1px solid var(--id-line);
  border-radius: 12px 12px 12px 4px;
  background: var(--id-paper);
  box-shadow: 0 10px 35px rgba(20, 54, 54, 0.055);
}

.chat-workbench::before {
  position: absolute;
  z-index: 5;
  top: 0;
  left: 0;
  width: 72px;
  height: 3px;
  background: var(--id-primary);
  content: '';
}

.chat-main {
  position: relative;
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  background: var(--id-paper);
}

.chat-sidebar-trigger {
  position: absolute;
  z-index: 20;
  top: 14px;
  left: 14px;
  display: inline-flex;
  width: 36px;
  height: 36px;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--id-line);
  border-radius: 8px;
  color: var(--id-ink-soft);
  background: var(--id-paper);
  box-shadow: 0 4px 12px rgba(20, 54, 54, 0.08);
  cursor: pointer;
}

.chat-sidebar-trigger:hover {
  border-color: var(--id-primary);
  color: var(--id-primary);
}

@media (max-width: 640px) {
  .chat-page {
    padding: 0;
  }

  .chat-workbench {
    border: 0;
    border-radius: 0;
    box-shadow: none;
  }
}
</style>

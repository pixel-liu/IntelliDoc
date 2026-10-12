<script setup lang="ts">
defineOptions({
  name: 'ConversationSidebar'
});

const collapsed = defineModel<boolean>('collapsed', { default: false });

const chatStore = useChatStore();
const { conversationId, sessionsLoading, filteredSessions, activeTab } = storeToRefs(chatStore);

onMounted(() => {
  chatStore.loadSessions();
});

function handleCollapse() {
  collapsed.value = true;
}

function handleNewChat() {
  chatStore.createNewSession();
}

function handleSelect(cid: string) {
  chatStore.switchSession(cid);
}

function handleArchive(cid: string) {
  chatStore.archiveSession(cid);
}

function handleUnarchive(cid: string) {
  chatStore.unarchiveSession(cid);
}

function setActiveTab(tab: 'active' | 'archived') {
  activeTab.value = tab;
}

function formatDate(dateStr?: string) {
  if (!dateStr) return '';
  const date = dayjs(dateStr);
  const now = dayjs();
  if (date.isSame(now, 'day')) {
    return date.format('HH:mm');
  }
  if (date.isSame(now, 'week')) {
    return date.format('ddd');
  }
  if (date.isSame(now, 'year')) {
    return date.format('MM-DD');
  }
  return date.format('YYYY-MM-DD');
}
</script>

<template>
  <div
    class="conversation-sidebar relative h-full shrink-0 flex flex-col overflow-hidden transition-[width] duration-200 ease-out"
    :class="collapsed ? 'w-0 min-w-0 border-r-0' : 'w-[260px] min-w-[260px]'"
  >
    <div class="conversation-sidebar__inner flex w-[260px] flex-1 flex-col overflow-hidden" :class="{ 'pointer-events-none invisible': collapsed }">
    <div class="conversation-sidebar__header flex items-center justify-between">
      <span class="text-15px font-600">对话列表</span>
      <div class="flex items-center gap-1">
        <NButton
          type="primary"
          size="small"
          secondary
          @click="handleNewChat"
        >
          <template #icon>
            <icon-material-symbols:add-rounded />
          </template>
          新对话
        </NButton>
        <NButton text size="tiny" @click="handleCollapse">
          <template #icon>
            <icon-material-symbols:left-panel-close-outline-rounded />
          </template>
        </NButton>
      </div>
    </div>

    <!-- Tabs -->
    <div class="conversation-sidebar__tabs flex">
      <div
        class="flex-1 cursor-pointer rounded-md px-3 py-1.5 text-center text-12px font-500 transition-all"
        :class="activeTab === 'active'
          ? 'bg-white text-[rgb(var(--primary-color))] shadow-sm dark:bg-[#FFFFFF14] dark:text-(--primary-color)'
          : 'text-#666 dark:text-#999'"
        @click="setActiveTab('active')"
      >
        活跃
      </div>
      <div
        class="flex-1 cursor-pointer rounded-md px-3 py-1.5 text-center text-12px font-500 transition-all"
        :class="activeTab === 'archived'
          ? 'bg-white text-[rgb(var(--primary-color))] shadow-sm dark:bg-[#FFFFFF14] dark:text-(--primary-color)'
          : 'text-#666 dark:text-#999'"
        @click="setActiveTab('archived')"
      >
        已归档
      </div>
    </div>

    <!-- List -->
    <div class="conversation-sidebar__list flex-1 overflow-y-auto">
      <NSpin :show="sessionsLoading" class="h-full">
        <TransitionGroup name="session-list" tag="div">
          <div
            v-if="filteredSessions.length === 0 && !sessionsLoading"
            class="flex flex-col items-center justify-center gap-3 py-16"
          >
            <icon-material-symbols:chat-outline-rounded class="text-36px color-#ccc dark:color-#444" />
            <span class="text-13px color-#aaa">{{ activeTab === 'active' ? '暂无对话记录' : '暂无归档对话' }}</span>
          </div>

          <div
            v-for="session in filteredSessions"
            :key="session.conversationId"
            class="conversation-session group flex cursor-pointer items-center gap-2.5 px-3 py-2.5 transition-all"
            :class="session.conversationId === conversationId
              ? 'conversation-session--active bg-[rgb(var(--primary-color)/0.08)]'
              : 'hover:bg-[rgb(var(--border-color)/0.3)] dark:hover:bg-[#FFFFFF08]'"
            @click="handleSelect(session.conversationId)"
          >
            <div class="shrink-0 flex h-7 w-7 items-center justify-center rounded-lg text-15px"
              :class="session.conversationId === conversationId
                ? 'bg-[rgb(var(--primary-color)/0.12)] text-[rgb(var(--primary-color))]'
                : 'bg-[rgb(var(--border-color)/0.2)] text-#999 dark:bg-[#FFFFFF0A]'"
            >
              <icon-material-symbols:chat-outline-rounded />
            </div>
            <div class="min-w-0 flex-1">
              <div
                class="truncate text-13px font-500"
                :class="session.conversationId === conversationId ? 'text-[rgb(var(--primary-color))]' : ''"
              >
                {{ session.title }}
              </div>
              <div class="text-11px color-#aaa">{{ formatDate(session.updatedAt) }}</div>
            </div>

            <!-- Action button -->
            <NPopconfirm v-if="activeTab === 'active'" @positive-click="handleArchive(session.conversationId)">
              <template #trigger>
                <NButton
                  class="shrink-0 transition-opacity"
                  :class="session.conversationId === conversationId ? '' : 'opacity-0 group-hover:opacity-100'"
                  text
                  size="tiny"
                  @click.stop
                >
                  <template #icon>
                    <icon-material-symbols:archive-outline-rounded class="text-15px color-#999 hover:color-#666" />
                  </template>
                </NButton>
              </template>
              归档后可在「已归档」中找回
            </NPopconfirm>
            <NButton
              v-else
              class="shrink-0 opacity-0 transition-opacity group-hover:opacity-100"
              text
              size="tiny"
              @click.stop="handleUnarchive(session.conversationId)"
            >
              <template #icon>
                <icon-material-symbols:unarchive-outline-rounded class="text-15px color-#999 hover:color-#666" />
              </template>
            </NButton>
          </div>
        </TransitionGroup>
      </NSpin>
    </div>
    </div>

  </div>
</template>

<style scoped>
.conversation-sidebar {
  border-right: 1px solid var(--id-line) !important;
  background: color-mix(in srgb, var(--id-canvas) 62%, var(--id-paper)) !important;
}

.conversation-sidebar__header {
  min-height: 66px;
  border-bottom: 1px solid var(--id-line);
  padding: 15px 14px 12px;
}

.conversation-sidebar__tabs {
  margin: 10px 12px 8px;
  border: 1px solid var(--id-line);
  border-radius: 7px;
  background: var(--id-paper) !important;
  padding: 3px;
}

.conversation-sidebar__tabs > div {
  border-radius: 5px;
  box-shadow: none !important;
}

.conversation-sidebar__list {
  padding: 4px 8px 14px;
}

.conversation-session {
  position: relative;
  margin-bottom: 2px;
  border: 1px solid transparent;
  border-radius: 7px;
}

.conversation-session:hover {
  border-color: var(--id-line);
  background: var(--id-paper) !important;
}

.conversation-session--active {
  border-color: color-mix(in srgb, var(--id-primary) 26%, var(--id-line));
  background: var(--id-primary-quiet) !important;
}

.session-list-enter-active,
.session-list-leave-active {
  transition: all 0.2s ease;
}
.session-list-enter-from,
.session-list-leave-to {
  opacity: 0;
  transform: translateX(-8px);
}
</style>

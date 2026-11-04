<template>
  <div class="ai-config-page">
    <PageHeader title="AI配置" description="控制正式用户端小辰的知识来源与每位用户的每日使用额度">
      <template #actions>
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新配置</el-button>
      </template>
    </PageHeader>

    <el-alert v-if="error" :title="friendlyError" type="warning" :closable="false" show-icon class="notice" />
    <el-alert v-if="status.applyError" :title="status.applyError" type="warning" :closable="false" show-icon class="notice" />
    <el-alert v-if="status.quotaApplyError" :title="status.quotaApplyError" type="warning" :closable="false" show-icon class="notice" />
    <el-alert v-if="legacyMode" type="info" :closable="false" show-icon class="notice"
              title="当前仍是已停用的“普通对话”历史配置：简单交流在三种新模式中同样能自然回答，请重新选择一种知识模式。" />

    <section class="summary-grid" aria-label="当前配置摘要">
      <StatCard label="当前小辰模式" :value="modeShortLabel(status.effectiveMode)" :icon="MagicStick" type="user"
                :loading="!loaded" :trend-type="status.applied ? 'positive' : 'negative'"
                :trend-text="status.applied ? (status.source === 'DATABASE' ? '管理员配置' : '部署默认值') : '已保存待生效'" />
      <StatCard label="每日使用上限" :value="dailyLimitText" :icon="Timer" type="visits" :loading="!loaded"
                trend-type="neutral" :trend-text="status.quotaApplied === false ? '已保存待生效' : '三种模式共用，按北京时间自然日恢复'" />
      <StatCard label="RAG 知识状态" :value="ragState().text" :icon="Document" type="article" :loading="!loaded"
                :trend-type="ragState().trendType" :trend-text="ragState().note" />
      <StatCard label="Wiki 知识状态" :value="wikiState().text" :icon="Reading" type="today" :loading="!loaded"
                :trend-type="wikiState().trendType" :trend-text="wikiState().note" />
    </section>

    <section class="panel-grid">
      <el-card class="panel" shadow="never">
        <template #header>
          <div class="panel-head">
            <span class="panel-title">小辰知识模式</span>
            <el-tag :type="status.applied ? 'success' : 'warning'" size="small" effect="plain">
              实际生效：{{ modeLabel(status.effectiveMode) }}
            </el-tag>
          </div>
        </template>
        <p class="hint">保存后立即用于新的回答；正在进行的回答不会中途切换。身份、业务工具和后台权限不受模式影响。</p>
        <el-radio-group v-model="selectedMode" :disabled="loading || saving || !canManage" class="mode-list">
          <el-radio v-for="mode in modes" :key="mode.mode" :value="mode.mode" :disabled="!mode.available" class="mode-item">
            <span class="mode-item__head">
              <span class="mode-item__title">{{ mode.label }}</span>
              <el-tag v-if="mode.mode === 'hybrid'" size="small" type="success">推荐</el-tag>
              <el-tag v-if="mode.mode === status.effectiveMode" size="small" effect="plain">当前生效</el-tag>
              <el-tag v-if="!mode.available" size="small" type="warning" effect="plain">暂不可用</el-tag>
            </span>
            <span class="mode-item__note">{{ mode.note }}</span>
          </el-radio>
        </el-radio-group>
        <div class="panel-actions">
          <el-button type="primary" :loading="saving"
                     :disabled="!canManage || selectedMode === status.desiredMode || !modeAvailable(selectedMode)"
                     @click="saveMode">保存工作模式</el-button>
          <span v-if="!canManage" class="hint">当前账号没有修改AI配置的权限。</span>
          <span v-else-if="selectedMode !== status.desiredMode" class="hint">保存后应用于正式用户端新的回答。</span>
          <span v-else-if="status.desiredMode === status.effectiveMode" class="hint">所选模式已在运行中生效。</span>
        </div>
      </el-card>

      <el-card class="panel" shadow="never">
        <template #header>
          <div class="panel-head">
            <span class="panel-title">每日使用额度</span>
            <el-tag :type="status.quotaApplied === false ? 'warning' : 'success'" size="small" effect="plain">
              当前生效 {{ dailyLimitText }}
            </el-tag>
          </div>
        </template>
        <p class="hint">默认 100 次。RAG、Wiki 和智能混合共用同一额度，每位用户独立计数，按北京时间自然日恢复。修改上限不会清空当天已用次数。</p>
        <div class="quota-row">
          <label class="quota-label" for="daily-limit-input">每位用户每日上限</label>
          <el-input-number id="daily-limit-input" v-model="dailyLimitInput" :min="1" :max="100000" :step="10"
                           :disabled="loading || saving || !canManage" />
          <span class="hint">次</span>
          <el-button type="primary" :loading="saving"
                     :disabled="!canManage || dailyLimitInput === status.desiredDailyLimit"
                     @click="saveQuota">保存额度</el-button>
        </div>
        <p class="hint">降低到低于当天已用次数时，剩余额度显示为 0，不会产生负数，也不会继续放行超额请求。智能混合内部的多轮模型与检索调用单独计入模型用量统计，不重复扣用户次数。</p>
        <p v-if="status.desiredDailyLimit !== status.dailyLimit" class="hint">
          已保存 {{ status.desiredDailyLimit }} 次，等待生效。
        </p>
      </el-card>
    </section>

    <el-card class="panel" shadow="never">
      <template #header>
        <div class="panel-head"><span class="panel-title">知识使用说明</span></div>
      </template>
      <ul class="explain-list">
        <li><strong>RAG 知识检索</strong>：检索管理员审校过的原始资料，适合规则、限制、额度、权限和故障类问题。</li>
        <li><strong>Wiki 知识问答</strong>：检索已审核发布的整理知识，适合有完整步骤的操作说明。</li>
        <li><strong>智能混合（推荐）</strong>：小辰按问题自行决定用 RAG、用 Wiki，或两侧都需要时才都查；“你好”“谢谢”这类简单交流不检索知识。</li>
        <li><strong>共同边界</strong>：三种模式共用同一套身份校验、站内业务工具与后台权限规则；查不到有效资料时小辰会直接说明不能确认，不会编造按钮、政策或业务结果。</li>
      </ul>
    </el-card>

    <el-card class="panel" shadow="never">
      <template #header>
        <div class="panel-head">
          <span class="panel-title">最近修改记录</span>
          <span class="hint">最近 {{ recentChanges.length }} 次修改</span>
        </div>
      </template>
      <DataTable :data="recentChanges" :show-id="false" :show-create-time="false" :show-actions="false"
                 empty-text="暂无管理员修改记录">
        <el-table-column prop="changedAt" label="修改时间" min-width="180" />
        <el-table-column label="配置项" width="150"><template #default="{ row }">{{ changeLabel(row) }}</template></el-table-column>
        <el-table-column label="变更" min-width="240"><template #default="{ row }">{{ changeText(row) }}</template></el-table-column>
        <el-table-column prop="operator" label="操作人" width="150" />
      </DataTable>
      <p v-if="status.updatedAt" class="hint">最近更新：{{ status.updatedAt }} · {{ status.updatedBy || '管理员' }}</p>
      <p class="hint">完整修改审计保留在服务端配置审计表中，此处只展示最近若干条，避免页面被超长表格占满。</p>
    </el-card>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh, MagicStick, Timer, Document, Reading } from '@element-plus/icons-vue'
import PageHeader from '@/components/management/PageHeader.vue'
import StatCard from '@/components/cards/StatCard.vue'
import DataTable from '@/components/data/DataTable.vue'
import { aiConfigApi } from '@/api/config'
import { knowledgeModeOptions, knowledgeModeLabel, knowledgeModeShortLabel } from '@/utils/aiPresentation'

const loading = ref(false)
const saving = ref(false)
const loaded = ref(false)
const error = ref('')
const canManage = ref(false)
const status = ref({})
const selectedMode = ref('')
const dailyLimitInput = ref(100)

const modes = computed(() => (status.value.modes?.length
  ? status.value.modes
  : knowledgeModeOptions.map((item) => ({ ...item, available: false, note: '暂不可用' }))))
const recentChanges = computed(() => status.value.recentChanges || [])
const dailyLimitText = computed(() => (status.value.dailyLimit == null ? '—' : `${status.value.dailyLimit} 次`))
const legacyMode = computed(() => !knowledgeModeOptions.some((item) => item.mode === status.value.effectiveMode)
  && Boolean(status.value.effectiveMode))
const friendlyError = computed(() => (/[\u3400-\u9fff]/.test(error.value) ? error.value : '配置暂未加载，请刷新重试。'))

function modeLabel(mode) { return knowledgeModeLabel(mode) }
function modeShortLabel(mode) { return knowledgeModeShortLabel(mode) }
function modeAvailable(mode) { return Boolean(modes.value.find((item) => item.mode === mode)?.available) }

function ragState() {
  const knowledge = status.value.knowledge || {}
  if (!loaded.value) return { text: '—', trendType: 'neutral', note: '正在读取知识状态' }
  if (!knowledge.ragReady) return { text: '不可用', trendType: 'negative', note: '知识索引未就绪，RAG 模式暂不可切换' }
  return {
    text: '可用', trendType: 'positive',
    note: knowledge.ragSources == null ? '已审校原始资料可检索' : `已审校 ${knowledge.ragSources} 条主题`,
  }
}

function wikiState() {
  const knowledge = status.value.knowledge || {}
  if (!loaded.value) return { text: '—', trendType: 'neutral', note: '正在读取知识状态' }
  if (!knowledge.wikiReady) return { text: '不可用', trendType: 'negative', note: '整理知识服务未就绪，Wiki 模式暂不可切换' }
  return {
    text: '可用', trendType: 'positive',
    note: knowledge.wikiPages == null ? '已发布整理知识可检索' : `已发布 ${knowledge.wikiPages} 篇`,
  }
}

function changeLabel(row) {
  if (row.label) return row.label
  return ['chat', 'rag', 'wiki', 'hybrid'].includes(row.newValue) || ['chat', 'rag', 'wiki', 'hybrid'].includes(row.oldValue)
    ? '小辰工作模式' : '每日使用额度'
}

function changeText(row) {
  if (changeLabel(row) === '小辰工作模式') return `${knowledgeModeLabel(row.oldValue)} → ${knowledgeModeLabel(row.newValue)}`
  return `${row.oldValue || '默认'} → ${row.newValue} 次`
}

function message(failure) { return failure?.response?.data?.msg || failure?.msg || failure?.message || '操作未完成，请稍后重试' }

function apply(data) {
  const view = data || {}
  status.value = view.status || {}
  canManage.value = Boolean(view.canManage)
  selectedMode.value = status.value.desiredMode || status.value.effectiveMode || ''
  dailyLimitInput.value = status.value.desiredDailyLimit ?? status.value.dailyLimit ?? 100
  loaded.value = true
}

async function load() {
  if (loading.value) return
  loading.value = true
  error.value = ''
  try { apply((await aiConfigApi.status()).data) }
  catch (failure) { error.value = message(failure) }
  finally { loading.value = false }
}

async function saveMode() {
  if (saving.value || !canManage.value) return
  saving.value = true
  error.value = ''
  try {
    apply((await aiConfigApi.setMode(selectedMode.value)).data)
    ElMessage.success(status.value.applied ? '工作模式已保存并生效' : '配置已保存，等待生效')
  } catch (failure) { error.value = message(failure) }
  finally { saving.value = false }
}

async function saveQuota() {
  if (saving.value || !canManage.value) return
  saving.value = true
  error.value = ''
  try {
    apply((await aiConfigApi.setDailyLimit(dailyLimitInput.value)).data)
    ElMessage.success('每日额度已保存并生效')
  } catch (failure) { error.value = message(failure) }
  finally { saving.value = false }
}

onMounted(load)
</script>

<style lang="scss" scoped>
// 仅使用 Element Plus 主题变量，浅色与暗黑模式随 html.dark 自动切换；
// 同时把 StatCard 依赖的旧变量桥接到主题变量，避免深色模式下出现浅色卡片。
.ai-config-page {
  --bg-card: var(--el-bg-color-overlay);
  --bg-card-muted: var(--el-fill-color-light);
  --text-primary: var(--el-text-color-primary);
  --text-regular: var(--el-text-color-regular);
  --text-muted: var(--el-text-color-secondary);
  --border: var(--el-border-color-lighter);
  --shadow-card: var(--el-box-shadow-lighter);
  --shadow-hover: var(--el-box-shadow-light);
  --chart-line: var(--el-color-primary);

  min-width: 0;
  padding-bottom: 8px;
  color: var(--el-text-color-primary);
}

.notice { margin-bottom: 16px; }

.summary-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 16px;
  margin-bottom: 16px;
}

.panel-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
  margin-bottom: 16px;
}

.panel {
  min-width: 0;
  border-radius: 18px;

  :deep(.el-card__header) { padding: 16px 20px; }
  :deep(.el-card__body) { padding: 18px 20px; }
}

.panel-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.panel-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.hint {
  margin: 0 0 8px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
  line-height: 1.8;
  overflow-wrap: anywhere;
}

.mode-list {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 10px;
  width: 100%;
  margin: 12px 0 4px;
}

.mode-item {
  height: auto;
  margin: 0;
  padding: 12px 14px;
  border: 1px solid var(--el-border-color);
  border-radius: 12px;
  white-space: normal;
  align-items: flex-start;
  transition: border-color 0.2s ease;

  :deep(.el-radio__label) {
    display: flex;
    flex-direction: column;
    gap: 4px;
    white-space: normal;
    line-height: 1.7;
    text-align: left;
  }

  &.is-checked { border-color: var(--el-color-primary); }
  &.is-disabled { opacity: 0.7; }
}

.mode-item__head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.mode-item__title {
  font-size: 14px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.mode-item__note {
  color: var(--el-text-color-secondary);
  font-size: 13px;
  overflow-wrap: anywhere;
}

.panel-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  margin-top: 16px;
}

.panel-actions > .el-button { margin-left: 0; }

.quota-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  margin: 12px 0;
}

.quota-label {
  font-size: 14px;
  color: var(--el-text-color-regular);
}

.quota-row :deep(.el-input-number) { width: 180px; max-width: 100%; }

.explain-list {
  margin: 0;
  padding-left: 20px;
  color: var(--el-text-color-regular);
  line-height: 1.9;

  li { margin-bottom: 6px; overflow-wrap: anywhere; }
  strong { color: var(--el-text-color-primary); }
}

@media (max-width: 1200px) {
  .summary-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .panel-grid { grid-template-columns: minmax(0, 1fr); }
}

@media (max-width: 700px) {
  .summary-grid { grid-template-columns: minmax(0, 1fr); }
  .panel-actions > .el-button,
  .quota-row :deep(.el-input-number),
  .quota-row > .el-button { width: 100%; margin-left: 0; }
}
</style>

<script setup>
import {computed, onMounted, reactive, ref} from 'vue';
import {api} from '/workbench.js';

const frequencyFields = [
  ['globalLimit', '窗口内最多尝试（次）', 1, 100],
  ['windowSeconds', '全局窗口（秒）', 1, 3600],
  ['cooldownSeconds', '同一联系人冷却（秒）', 0, 86400],
  ['retrySeconds', '首次重试间隔（秒）', 1, 3600]
];

const templates = ref([]);
const templateDraft = reactive({name: '', body: ''});
const templateList = reactive({loading: true, error: ''});
const templateSave = reactive({pending: false, status: '', error: ''});
const frequency = reactive(Object.fromEntries(frequencyFields.map(([name]) => [name, ''])));
const frequencyLoad = reactive({loading: true, ready: false});
const frequencySave = reactive({pending: false, status: '', error: ''});
const frequencyDisabled = computed(() => !frequencyLoad.ready || frequencySave.pending);

async function loadTemplates() {
  templateList.loading = true;
  templateList.error = '';
  try {
    templates.value = await api('templates');
  } catch (error) {
    templateList.error = error.message;
  } finally {
    templateList.loading = false;
  }
}

function setFrequency(rules) {
  for (const [name] of frequencyFields) frequency[name] = rules[name];
}

async function loadFrequency() {
  try {
    setFrequency(await api('frequency'));
    frequencyLoad.ready = true;
  } catch (error) {
    frequencySave.error = error.message;
  } finally {
    frequencyLoad.loading = false;
  }
}

async function saveTemplate() {
  if (templateSave.pending) return;
  const name = templateDraft.name.trim();
  const body = templateDraft.body.trim();
  Object.assign(templateSave, {pending: true, status: '', error: ''});
  try {
    if (!name || !body) throw new Error('请输入模板名称和问候文案。');
    await api('templates', 'POST', {name, body});
    Object.assign(templateDraft, {name: '', body: ''});
    templateSave.status = '模板已保存。';
    await loadTemplates();
  } catch (error) {
    templateSave.error = error.message;
  } finally {
    templateSave.pending = false;
  }
}

async function saveFrequency(event) {
  if (frequencyDisabled.value) return;
  const rules = Object.fromEntries(frequencyFields.map(([name]) => [name, event.currentTarget.elements[name].valueAsNumber]));
  Object.assign(frequencySave, {pending: true, status: '', error: ''});
  try {
    setFrequency(await api('frequency', 'PUT', rules));
    frequencySave.status = '频控已保存。';
  } catch (error) {
    frequencySave.error = error.message;
  } finally {
    frequencySave.pending = false;
  }
}

onMounted(() => {
  void loadTemplates();
  void loadFrequency();
});
</script>

<template>
  <section class="panel panel-body stack" aria-labelledby="templates-title">
    <h2 id="templates-title">问候模板</h2>
    <div id="template-list" class="stack" :aria-busy="templateList.loading">
      <div v-for="template in templates" :key="template.id" class="stack template-card">
        <h3>{{ template.name }}</h3>
        <div class="workbench-preview">{{ template.body }}</div>
      </div>
      <p v-if="!templates.length && templateList.loading" role="status">正在加载模板…</p>
      <p v-else-if="!templates.length && !templateList.error">暂时没有模板。</p>
    </div>
    <p id="templates-error" class="message error" role="alert" :hidden="!templateList.error">{{ templateList.error }}</p>
    <form id="template-form" class="workbench-form" :aria-busy="templateSave.pending" @submit.prevent="saveTemplate">
      <label>模板名称<input id="template-name" v-model="templateDraft.name" name="name" maxlength="100" required :disabled="templateSave.pending"></label>
      <label>问候文案<textarea id="template-body" v-model="templateDraft.body" name="body" rows="4" maxlength="1000" required aria-describedby="template-help" :disabled="templateSave.pending"></textarea></label>
      <p id="template-help" class="workbench-note"><code v-pre>{{display_name}}</code> 会替换成用户名。修改内容可另存为新模板。</p>
      <div class="actions">
        <button class="primary" type="submit" :disabled="templateSave.pending">{{ templateSave.pending ? '正在保存…' : '新增模板' }}</button>
      </div>
    </form>
    <p id="template-status" class="message" role="status" :hidden="!templateSave.status">{{ templateSave.status }}</p>
    <p id="template-error" class="message error" role="alert" :hidden="!templateSave.error">{{ templateSave.error }}</p>
  </section>

  <section class="panel panel-body stack" aria-labelledby="frequency-title">
    <h2 id="frequency-title">全局频控</h2>
    <p v-if="frequencyLoad.loading" role="status">正在加载频控…</p>
    <form id="frequency-form" class="workbench-form" :aria-busy="frequencySave.pending" @submit.prevent="saveFrequency">
      <label v-for="[name, label, min, max] in frequencyFields" :key="name">
        {{ label }}
        <input v-model="frequency[name]" :name="name" type="number" :min="min" :max="max" required :disabled="frequencyDisabled">
      </label>
      <p class="workbench-note">保存后适用于所有任务的后续发送尝试。暂时失败最多重试 2 次，仍受全局频控和联系人冷却限制。</p>
      <div class="actions">
        <button class="primary" type="submit" :disabled="frequencyDisabled">{{ frequencySave.pending ? '正在保存…' : '保存频控' }}</button>
      </div>
    </form>
    <p id="frequency-status" class="message" role="status" :hidden="!frequencySave.status">{{ frequencySave.status }}</p>
    <p id="frequency-error" class="message error" role="alert" :hidden="!frequencySave.error">{{ frequencySave.error }}</p>
  </section>
</template>

import {api, formatTime, node, reasonLabel, show} from './workbench.js';

const actions = new Map(Object.entries({
  LEADS_IMPORTED: '导入线索', LEADS_SCORED: '计算评分', LEAD_DECISION: '更新入池决定',
  RULES_UPDATED: '更新评分规则', CONTACT_UPDATED: '提交联系授权操作', CONTACT_EFFECTIVE_STATUS: '联系授权有效状态',
  SEMANTIC_MODE_CHANGED: '切换筛选模式', SEMANTIC_QUEUED: '提交语义分析', SEMANTIC_STARTED: '开始语义分析',
  SEMANTIC_SUCCEEDED: '语义分析完成', SEMANTIC_FAILED: '语义分析失败', SEMANTIC_STALE: '语义结果过期',
  SEMANTIC_REUSED: '复用语义分析结果', SEMANTIC_RETRY_SCHEDULED: '安排语义重试',
  TEMPLATE_CREATED: '创建模板', FREQUENCY_UPDATED: '更新频控',
  TASK_CREATED: '创建任务', TASK_START: '启动任务', TASK_PAUSE: '暂停任务',
  TASK_RESUME: '恢复任务', TASK_COMPLETED: '完成任务', DUPLICATE_CONTACT: '去除重复联系人',
  TARGET_ACCEPTED: '模拟受理', TARGET_DELIVERED: '模拟送达', TARGET_RETRY: '等待重试',
  TARGET_FAILED: '派发失败', TARGET_UNCERTAIN: '待核对结果', TARGET_SKIPPED: '拦截目标'
}));
const actors = new Map([['local_operator', '本地操作员'], ['system', '系统']]);
const pageSize = 200;
const form = document.getElementById('audit-filters');
const rows = document.getElementById('audit-rows');
const resultRegion = document.getElementById('audit-results');
const pagination = document.getElementById('audit-pagination');
const more = document.getElementById('audit-more');
const taskLink = document.getElementById('audit-task-link');
const taskNames = new Map();
let query = new URLSearchParams();
let beforeId;
let requestId = 0;
let loaded = 0;

function taskHref(id) {
  return '/tasks.html?' + new URLSearchParams({id});
}

function targetHref(event) {
  const params = new URLSearchParams({id: event.taskId, leadId: event.leadId});
  if (event.attemptNo != null) params.set('attemptNo', event.attemptNo);
  return '/tasks.html?' + params;
}

function filterTime(value, end = false) {
  if (!value) return '';
  const seconds = value.length === 16 ? `${value}:00` : value;
  return `${seconds}${end ? '.999999999' : ''}+08:00`;
}

function restoreTime(value) {
  if (!value) return '';
  const timestamp = Date.parse(value);
  if (!Number.isFinite(timestamp)) return '';
  return new Date(timestamp + 8 * 60 * 60 * 1000).toISOString().slice(0, 19);
}

function renderRow(event) {
  const row = node('tr');
  const time = node('time', formatTime(event.occurredAt));
  time.dateTime = event.occurredAt;
  const timeCell = node('td');
  timeCell.append(time);
  const eventCell = node('td');
  const eventLabel = actions.get(event.action) ?? event.action;
  const targetLink = event.taskId != null && event.leadId != null && event.action.startsWith('TARGET_')
    ? targetHref(event) : null;
  if (targetLink) {
    const link = node('a', eventLabel);
    link.href = targetLink;
    link.setAttribute('aria-label', `${eventLabel}，线索 #${event.leadId}${event.attemptNo == null ? '' : `，第 ${event.attemptNo} 次尝试`}发送明细`);
    eventCell.append(link);
    if (event.attemptNo != null) eventCell.append(node('p', `第 ${event.attemptNo} 次尝试`));
  } else {
    eventCell.textContent = eventLabel;
  }
  const taskCell = node('td', event.taskId == null ? '—' : '');
  if (event.taskId != null) {
    const link = node('a');
    const taskId = String(event.taskId);
    const name = taskNames.get(taskId);
    link.textContent = name ? `${name} · #${taskId}` : `任务 #${taskId}`;
    link.href = targetLink ?? taskHref(taskId);
    if (targetLink) link.title = '打开这条事件对应的发送明细';
    link.dataset.taskId = taskId;
    taskCell.append(link);
  }
  const leadCell = node('td', event.leadId ?? '—');
  if (targetLink) {
    const link = node('a', event.leadId);
    link.href = targetLink;
    link.setAttribute('aria-label', `线索 #${event.leadId} 发送明细`);
    leadCell.replaceChildren(link);
  }
  row.append(timeCell, eventCell, taskCell, leadCell, node('td', actors.get(event.actor) ?? event.actor),
    node('td', reasonLabel(event.reason)));
  return row;
}

async function load(reset) {
  const current = ++requestId;
  const params = new URLSearchParams(query);
  if (!reset) params.set('beforeId', beforeId);
  resultRegion.setAttribute('aria-busy', 'true');
  more.disabled = true;
  show('audit-error');
  if (reset) {
    beforeId = undefined;
    loaded = 0;
    pagination.hidden = true;
    rows.replaceChildren();
  }
  show('audit-status', reset ? '正在查询审计记录…' : '正在加载更早记录…');
  try {
    const events = await api('audit?' + params);
    if (current !== requestId) return;
    rows.append(...events.map(renderRow));
    loaded += events.length;
    beforeId = events.length === pageSize ? events.at(-1).id : undefined;
    show('audit-status', loaded ? `已显示 ${loaded} 条事件` : '当前筛选条件下没有审计记录。');
  } catch (error) {
    if (current !== requestId) return;
    show('audit-status', loaded ? `已显示 ${loaded} 条事件` : '');
    show('audit-error', error.message);
  } finally {
    if (current === requestId) {
      resultRegion.setAttribute('aria-busy', 'false');
      pagination.hidden = beforeId === undefined;
      more.disabled = beforeId === undefined;
    }
  }
}

function applyFilters() {
  const data = new FormData(form);
  const since = filterTime(data.get('since'));
  const until = filterTime(data.get('until'), true);
  if (since && until && Date.parse(since) > Date.parse(until)) {
    show('audit-error', '开始时间应早于或等于结束时间。');
    form.elements.until.focus();
    return;
  }
  const taskId = data.get('taskId');
  query = new URLSearchParams();
  for (const [key, value] of Object.entries({taskId, leadId: data.get('leadId'), attemptNo: data.get('attemptNo'), action: data.get('action'), since, until})) {
    if (value) query.set(key, value);
  }
  history.replaceState(null, '', query.size ? `/audit.html?${query}` : '/audit.html');
  taskLink.hidden = !taskId;
  if (taskId) taskLink.href = taskHref(taskId);
  load(true);
}

async function loadTaskNames() {
  try {
    const tasks = await api('tasks');
    const options = tasks.map(task => {
      const id = String(task.id);
      taskNames.set(id, task.name);
      const option = node('option');
      option.value = id;
      option.label = task.name;
      return option;
    });
    document.getElementById('audit-task-options').replaceChildren(...options);
    for (const link of rows.querySelectorAll('[data-task-id]')) {
      const id = link.dataset.taskId;
      const name = taskNames.get(id);
      if (name) link.textContent = `${name} · #${id}`;
    }
  } catch {
    // The task numbers remain available when task names cannot be loaded.
  }
}

for (const [code, label] of actions) {
  const option = node('option', label);
  option.value = code;
  form.elements.action.append(option);
}
const initial = new URLSearchParams(location.search);
for (const key of ['taskId', 'leadId', 'attemptNo']) {
  const id = initial.get(key);
  if (id && /^[1-9][0-9]*$/.test(id)) form.elements[key].value = id;
}
if (actions.has(initial.get('action'))) form.elements.action.value = initial.get('action');
form.elements.since.value = restoreTime(initial.get('since'));
form.elements.until.value = restoreTime(initial.get('until'));
form.addEventListener('submit', event => {
  event.preventDefault();
  applyFilters();
});
document.getElementById('audit-clear').addEventListener('click', () => {
  form.reset();
  applyFilters();
});
more.addEventListener('click', () => load(false));
applyFilters();
loadTaskNames();

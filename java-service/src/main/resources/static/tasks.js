import {api, node, show, formatTime, statusLabel, reasonLabel} from './workbench.js';

const byId = id => document.getElementById(id);
const params = new URLSearchParams(location.search);
const taskId = /^[1-9]\d*$/.test(params.get('id') ?? '') ? params.get('id') : null;
const incomingLead = /^[1-9]\d*$/.test(params.get('leadId') ?? '') ? params.get('leadId') : null;
const incomingAttempt = /^[1-3]$/.test(params.get('attemptNo') ?? '') ? params.get('attemptNo') : null;
const draftForm = byId('task-draft-form');
const templateField = byId('task-template');
const draftRows = byId('draft-targets');
const targetRows = byId('task-target-rows');
const targetDialog = byId('target-details');
const controls = [['DRAFT', 'start'], ['RUNNING', 'pause'], ['PAUSED', 'resume']];
const draftStorageKey = 'outreach.pending-draft';
const leadNames = new Map();
const targetViews = new Map();
let leads = [];
let templates = [];
let draftKey;
let pendingDraft;
let task;
let busy = false;
let draftLoaded = false;
let pollingTimer;
let detailRequest = 0;
let activeTargetId;
let activeAttempt;
let openedFromAudit = false;
let detailStale = false;

function link(label, url) {
  const anchor = node('a', label);
  anchor.href = url;
  return anchor;
}

function row(cells) {
  const tr = node('tr');
  for (const value of cells) {
    const cell = node('td');
    if (value instanceof Node) cell.append(value);
    else cell.textContent = value ?? '—';
    tr.append(cell);
  }
  return tr;
}

function taskLink(id) {
  return '/tasks.html?id=' + encodeURIComponent(id);
}

function leadName(id) {
  return leadNames.get(id) ?? `线索 #${id}`;
}

function badge(code, text = statusLabel(code)) {
  const value = node('span', text);
  value.className = 'workbench-status';
  value.dataset.status = code;
  return value;
}

async function loadTasks() {
  byId('task-list').hidden = false;
  try {
    const entries = await api('tasks');
    byId('task-list-rows').replaceChildren(...entries.map(entry => {
      const tr = row([link(entry.name, taskLink(entry.id)), badge(entry.status),
        `#${entry.templateId}`, formatTime(entry.createdAt)]);
      tr.dataset.taskId = entry.id;
      tr.dataset.status = entry.status;
      return tr;
    }));
    show('task-list-empty', entries.length ? '' : '暂无批量任务。');
  } catch (error) {
    show('task-error', error.message);
  }
}

function storedDraft() {
  try {
    const value = JSON.parse(sessionStorage.getItem(draftStorageKey));
    if (value?.key && value?.input?.leadIds?.length) return value;
  } catch {
    // The current tab can still retry with its in-memory draft.
  }
  return undefined;
}

function rememberDraft(value) {
  pendingDraft = value;
  try {
    if (value) sessionStorage.setItem(draftStorageKey, JSON.stringify(value));
    else sessionStorage.removeItem(draftStorageKey);
  } catch {
    // The current tab can still retry with its in-memory draft.
  }
}

function nextDraftKey() {
  draftKey = 'workbench-' + crypto.randomUUID();
  byId('idempotency-key').textContent = draftKey;
}

function pickedIds() {
  return [...draftRows.querySelectorAll('input:checked')].map(input => Number(input.value));
}

function updateDraft() {
  const ids = pendingDraft?.input.leadIds ?? pickedIds();
  const templateId = pendingDraft?.input.templateId ?? Number(templateField.value);
  const template = templates.find(item => item.id === templateId);
  const firstLead = leads.find(lead => lead.id === ids[0]);
  byId('draft-count').textContent = `已选 ${ids.length} 条，最多 100 条`;
  byId('select-targets').textContent = leads.length > 100 ? '选择前 100 条' : '全选目标';
  byId('message-preview').textContent = template && firstLead
    ? template.body.replaceAll('{{display_name}}', () => firstLead.username)
    : '选择模板和目标后可预览。';
  const locked = busy || Boolean(pendingDraft);
  byId('task-name').disabled = locked;
  templateField.disabled = locked || !templates.length;
  byId('select-targets').disabled = locked || !leads.length;
  byId('clear-targets').disabled = locked || !ids.length;
  for (const input of draftRows.querySelectorAll('input')) input.disabled = locked;
  byId('save-draft').disabled = busy || (!pendingDraft && (!draftLoaded || !template || ids.length === 0 || ids.length > 100));
  byId('save-draft').textContent = busy ? '正在保存…' : pendingDraft ? '重试保存草稿' : '保存草稿';
}

async function loadDraft() {
  byId('task-title').textContent = '创建批量任务';
  byId('task-subtitle').textContent = '选择目标池成员与问候模板。';
  byId('task-list-link').hidden = false;
  byId('new-task').hidden = true;
  byId('task-draft').hidden = false;
  pendingDraft = storedDraft();
  if (pendingDraft) {
    draftKey = pendingDraft.key;
    byId('idempotency-key').textContent = draftKey;
    byId('task-name').value = pendingDraft.input.name;
    show('draft-error', '上次保存结果待确认。重试会使用原请求和幂等键。');
  } else {
    nextDraftKey();
  }
  updateDraft();
  try {
    [leads, templates] = await Promise.all([api('leads?selected=true'), api('templates')]);
    const checked = new Set(pendingDraft?.input.leadIds ?? (params.get('leadIds') ?? '').split(',').map(Number));
    templateField.replaceChildren(...templates.map(template => new Option(template.name, template.id)));
    if (pendingDraft) templateField.value = pendingDraft.input.templateId;
    byId('template-help').prepend(templates.length ? '' : '请先创建问候模板。');
    byId('draft-empty').hidden = Boolean(leads.length);
    byId('draft-table').hidden = !leads.length;
    draftRows.replaceChildren(...leads.map(lead => {
      const label = node('label');
      label.className = 'check';
      const checkbox = node('input');
      checkbox.type = 'checkbox';
      checkbox.name = 'leadIds';
      checkbox.value = lead.id;
      checkbox.checked = checked.has(lead.id);
      label.append(checkbox, lead.username);
      return row([label, `${lead.region || '—'}／${lead.language || '—'}`, lead.score]);
    }));
    draftLoaded = true;
  } catch (error) {
    show('task-error', error.message);
  }
  updateDraft();
}

async function saveDraft(event) {
  event.preventDefault();
  if (busy) return;
  const input = pendingDraft?.input ?? {
    name: byId('task-name').value.trim(), templateId: Number(templateField.value), leadIds: pickedIds()
  };
  if (!input.name || !input.templateId || input.leadIds.length === 0 || input.leadIds.length > 100) {
    show('draft-error', '请输入任务名称、选择模板和 1–100 个目标。');
    return;
  }
  rememberDraft({key: draftKey, input});
  busy = true;
  updateDraft();
  show('draft-error');
  try {
    const saved = await api('tasks', 'POST', input, {'Idempotency-Key': draftKey});
    rememberDraft(undefined);
    location.replace(taskLink(saved.id) + '&saved=1');
    return;
  } catch (error) {
    if ([400, 404, 409, 422].includes(error.status)) {
      rememberDraft(undefined);
      if (error.status === 409) nextDraftKey();
      show('draft-error', reasonLabel(error.message));
    } else {
      show('draft-error', '保存结果待确认。请重试，系统会使用原请求和幂等键。');
    }
  }
  busy = false;
  updateDraft();
}

function showTarget(target) {
  byId('target-title').textContent = `${leadName(target.leadId)} · 发送明细`;
  byId('target-status').textContent = `${statusLabel(target.status)} · ${reasonLabel(target.reason)} · ${target.contact ?? '无联系方式'}`;
  byId('target-retry').textContent = `发送尝试 ${target.attempts} 次 · 重试 ${target.retries} 次`;
  byId('target-message').textContent = target.messagePreview;
  const auditQuery = new URLSearchParams({taskId: task.id, leadId: target.leadId});
  byId('target-audit').href = '/audit.html?' + auditQuery;
  const attempts = task.attempts.filter(attempt => attempt.targetId === target.id);
  const matched = attempts.some(attempt => String(attempt.attemptNo) === activeAttempt);
  show('target-position', activeAttempt ? (matched ? `已定位到第 ${activeAttempt} 次尝试。`
    : `未找到第 ${activeAttempt} 次尝试，已打开该目标的现有记录。`) : '');
  const rows = byId('attempt-rows');
  const signature = JSON.stringify([target.id, activeAttempt, attempts]);
  if (rows.dataset.signature === signature) return;
  rows.dataset.signature = signature;
  const expanded = new Set([...rows.querySelectorAll('details[open]')]
    .map(proof => proof.dataset.attempt));
  byId('no-attempts').hidden = attempts.length > 0;
  byId('no-attempts').textContent = target.status === 'SKIPPED'
    ? `已拦截，未发送。原因：${reasonLabel(target.reason)}。` : '暂无发送尝试。';
  byId('attempt-table').hidden = !attempts.length;
  byId('message-proof-help').hidden = !attempts.some(attempt => attempt.messageMatchesSnapshot === true);
  rows.replaceChildren(...attempts.map(attempt => {
    const proof = node('div');
    const verification = attempt.messageMatchesSnapshot;
    const state = verification === true ? ['DELIVERED', '与草稿一致']
      : verification === false ? ['FAILED', '与草稿不同'] : ['PENDING', '无校验记录'];
    proof.append(badge(...state));
    if (attempt.messageSha256) {
      const details = node('details');
      details.dataset.attempt = `${target.id}/${attempt.attemptNo}`;
      details.open = expanded.has(details.dataset.attempt);
      details.append(node('summary', '查看技术指纹'), node('code', attempt.messageSha256));
      proof.append(details);
    }
    const query = new URLSearchParams(auditQuery);
    query.set('attemptNo', attempt.attemptNo);
    const audit = attempt.auditAvailable ? link('本次审计', '/audit.html?' + query) : '—';
    const tr = row([attempt.attemptNo, formatTime(attempt.attemptedAt), reasonLabel(attempt.outcome), proof, audit]);
    tr.dataset.attemptNo = attempt.attemptNo;
    if (String(attempt.attemptNo) === activeAttempt) {
      tr.dataset.focused = '';
      tr.tabIndex = -1;
    }
    return tr;
  }));
}

function openTarget(target, attempt = null) {
  activeTargetId = target.id;
  activeAttempt = attempt;
  showTarget(target);
  targetDialog.showModal();
  byId('attempt-rows').querySelector('[data-focused]')?.focus();
}

function updateTargetRow(target) {
  let cells = targetViews.get(target.id);
  if (!cells) {
    const name = node('strong');
    const contact = node('p');
    const lead = node('div');
    lead.append(name, contact);
    const state = badge(target.status);
    const reason = node('p');
    const stateInfo = node('div');
    stateInfo.append(state, reason);
    const counts = node('span');
    const details = node('button', '发送明细');
    details.type = 'button';
    details.dataset.targetId = target.id;
    const tr = row([lead, stateInfo, counts, details]);
    tr.dataset.leadId = target.leadId;
    targetRows.append(tr);
    cells = {tr, name, contact, state, reason, counts, details};
    targetViews.set(target.id, cells);
  }
  cells.tr.dataset.status = target.status;
  cells.name.textContent = leadName(target.leadId);
  cells.contact.textContent = target.contact ?? '无联系方式';
  cells.state.textContent = statusLabel(target.status);
  cells.state.dataset.status = target.status;
  cells.reason.textContent = reasonLabel(target.reason);
  cells.counts.textContent = `${target.attempts}／${target.retries}`;
  cells.details.setAttribute('aria-label', `${leadName(target.leadId)} 发送明细`);
}

function renderDetail(next) {
  task = next;
  byId('task-title').textContent = task.name;
  byId('task-subtitle').textContent = `任务 #${task.id} · 模板 #${task.templateId} · 创建于 ${formatTime(task.createdAt)}`;
  const status = byId('task-status');
  if (status.dataset.status !== task.status) {
    status.dataset.status = task.status;
    status.textContent = statusLabel(task.status);
  }
  byId('task-audit').href = '/audit.html?taskId=' + task.id;
  byId('task-audit').hidden = false;
  byId('task-detail').hidden = false;
  byId('refresh-task').hidden = false;
  byId('refresh-task').disabled = busy;
  for (const [state, action] of controls) {
    const button = byId(action + '-task');
    button.hidden = task.status !== state;
    button.disabled = busy || detailStale;
  }
  const stats = Object.entries(task.stats).map(([key, count]) => {
    return badge(key, `${key === 'total' ? '总目标' : statusLabel(key)} ${count}`);
  });
  byId('task-stats').replaceChildren(...stats, badge('attempts', `发送尝试 ${task.attempts.length}`));
  const skipped = task.targets.filter(target => target.status === 'SKIPPED');
  const withoutContact = skipped.filter(target => target.reason === 'NO_CONTACT').length;
  const withAttempts = task.targets.filter(target => target.attempts > 0).length;
  const attemptedSummary = task.status === 'DRAFT' ? '' : `已有 ${withAttempts} 个目标产生发送尝试。`;
  const skippedSummary = skipped.length ? `已拦截 ${skipped.length} 个目标${withoutContact ? `，其中 ${withoutContact} 个无联系方式` : ''}。` : '';
  show('task-dispatch-summary', attemptedSummary + skippedSummary);
  for (const target of task.targets) updateTargetRow(target);
  if (incomingLead && !openedFromAudit) {
    openedFromAudit = true;
    const requested = task.targets.find(target => String(target.leadId) === incomingLead);
    if (requested) openTarget(requested, incomingAttempt);
    else show('task-feedback', `该任务没有线索 #${incomingLead} 的发送明细。`);
  }
  const activeTarget = task.targets.find(target => target.id === activeTargetId);
  if (targetDialog.open && activeTarget) showTarget(activeTarget);
}

function scheduleDetailRefresh() {
  clearTimeout(pollingTimer);
  if (!detailStale && (task?.status === 'RUNNING' || task?.targets.some(target => target.status === 'ACCEPTED'))) {
    pollingTimer = setTimeout(refreshDetail, 900);
  }
}

async function refreshDetail() {
  if (busy) return;
  const request = ++detailRequest;
  clearTimeout(pollingTimer);
  try {
    const next = await api('tasks/' + taskId);
    if (request !== detailRequest) return;
    detailStale = false;
    show('task-error');
    renderDetail(next);
  } catch (error) {
    if (request !== detailRequest) return;
    detailStale = true;
    if (!task) byId('task-subtitle').textContent = '任务状态读取失败。';
    show('task-feedback');
    show('task-error', `${reasonLabel(error.message)} 点击「刷新状态」重试。`);
    if (task) renderDetail(task);
  } finally {
    if (request === detailRequest) scheduleDetailRefresh();
  }
}

async function controlTask(action) {
  if (!task || busy || detailStale) return;
  busy = true;
  detailRequest++;
  clearTimeout(pollingTimer);
  renderDetail(task);
  show('task-feedback', '正在更新任务状态…');
  try {
    const next = await api(`tasks/${taskId}/${action}`, 'POST');
    show('task-error');
    renderDetail(next);
    show('task-feedback', {start:'模拟发送已启动。', pause:'已暂停新增发送尝试。', resume:'模拟发送已恢复。'}[action]);
  } catch (error) {
    show('task-feedback');
    if ([undefined, 409].includes(error.status) || error.status >= 500) {
      busy = false;
      await refreshDetail();
      if (!detailStale) show('task-feedback', '已读取服务器状态，请根据当前状态继续。');
    } else {
      show('task-error', reasonLabel(error.message));
    }
  } finally {
    busy = false;
    renderDetail(task);
    scheduleDetailRefresh();
  }
}

function loadDetail() {
  byId('task-list-link').hidden = false;
  byId('new-task').hidden = true;
  byId('refresh-task').hidden = false;
  byId('task-subtitle').textContent = '正在读取任务状态…';
  if (params.has('saved')) show('task-feedback', '草稿已保存。');
  refreshDetail();
  api('leads').then(entries => {
    for (const lead of entries) leadNames.set(lead.id, lead.username);
    if (task) renderDetail(task);
  }).catch(() => {});
}

draftForm.addEventListener('submit', saveDraft);
draftForm.addEventListener('change', updateDraft);
byId('select-targets').addEventListener('click', () => {
  for (const [index, input] of [...draftRows.querySelectorAll('input')].entries()) input.checked = index < 100;
  updateDraft();
});
byId('clear-targets').addEventListener('click', () => {
  for (const input of draftRows.querySelectorAll('input')) input.checked = false;
  updateDraft();
});
byId('refresh-task').addEventListener('click', refreshDetail);
for (const [, action] of controls) byId(action + '-task').addEventListener('click', () => controlTask(action));
targetRows.addEventListener('click', event => {
  const button = event.target.closest('button[data-target-id]');
  const target = task?.targets.find(item => item.id === Number(button?.dataset.targetId));
  if (!target) return;
  openTarget(target);
});

if (taskId) loadDetail();
else if (params.has('new')) loadDraft();
else loadTasks();

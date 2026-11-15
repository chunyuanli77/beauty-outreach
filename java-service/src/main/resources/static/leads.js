import {parseLeadFile} from './lead-file.js';
import {api, node, reasonLabel, show} from './workbench.js';
import {initSemantic} from './semantic-ui.js';
import {createApp, h, shallowRef} from './vendor/vue.runtime.esm-browser.prod.js';

const byId = (id) => document.getElementById(id);
const importForm = byId('import-form');
const fileInput = byId('lead-file');
const importButton = importForm.querySelector('button');
const search = byId('lead-search');
const pool = byId('pool-only');
const ruleForm = byId('rule-form');
const detailDialog = byId('lead-details');
const contactForm = byId('contact-form');
const ruleFields = ['core', 'intent', 'exclude', 'regions', 'languages'];
const pageSize = 25;
const csvHeader = 'username,source_url,platform_id,bio,region,language\r\n';
byId('csv-template').href = 'data:text/csv;charset=utf-8,' + encodeURIComponent(csvHeader);

let leads = [];
let rules;
let currentPage = 0;
let activeLead;
let mutating = false;
let contact;
let contactRequest = 0;
const semantic = initSemantic({renderLeads, reloadLeads, currentLead: () => detailDialog.open ? activeLead : null});
const leadView = shallowRef({items: [], empty: '正在加载线索…'});
const metricLabels = ['已导入线索', '当前目标池', '待规则评分'];

createApp({render: () => h('dl', {class: 'overview-metrics', 'aria-live': 'polite'},
  metricLabels.map((label, index) => h('div', {class: 'overview-item', key: label}, [
    h('dt', {class: 'overview-label'}, label),
    h('dd', {class: 'overview-value'}, leadView.value.metrics?.[index] ?? '—')
  ])))}).mount('#lead-overview');

createApp({render: () => leadView.value.items.length
  ? leadView.value.items.map(({lead, analysis}) => h('tr', {key: lead.id}, [
    h('td', [h('strong', {class: 'lead-row-name'}, lead.username), h('p', {class: 'lead-row-bio'}, lead.bio)]),
    h('td', `${lead.region || '—'} / ${lead.language || '—'}`),
    h('td', [h('span', {class: 'lead-score'}, scoreLabel(lead))]),
    h('td', [analysis.selectable && h('label', {class: 'check'}, [
      h('input', {type: 'checkbox', checked: analysis.checked, 'aria-label': `选择 ${lead.username} 进行语义分析`,
        onChange: event => {
          const check = event.currentTarget;
          if (semantic.setSelection(lead.id, check.checked)) renderLeads();
          else check.checked = false;
        }}),
      h('span', '选择')
    ]), h('p', analysis.label)]),
    h('td', [h('span', {class: 'pool-status', 'data-status': lead.selected ? 'selected'
      : lead.manual_decision === 'REJECT' ? 'rejected' : 'idle'}, selectionLabel(lead))]),
    h('td', [h('button', {type: 'button', 'aria-label': `查看 ${lead.username} 的详情`, onClick: () => openLead(lead)}, '详情')])
  ])) : h('tr', [h('td', {colspan: 6, class: 'lead-empty'}, leadView.value.empty)])
}).mount('#lead-rows');

function normalized(value) {
  return value.normalize('NFKC').trim().toLowerCase();
}

function scoreLabel(lead) {
  if (lead.score === null) return '待评分';
  return rules && lead.rule_version !== rules.version ? '待重评' : `${lead.score} 分`;
}

function selectionLabel(lead) {
  if (lead.manual_decision === 'APPROVE') return '人工入池';
  if (lead.manual_decision === 'REJECT') return '人工拒绝';
  return lead.selected ? '自动入池' : '未入池';
}

function renderScoreHits(lead) {
  const labels = {core:'核心词 +60', intent:'意向词 +25', exclude:'排除词 −40', region:'地区命中',
    language:'语言命中', review:'建议人工复核'};
  const grouped = new Map();
  for (const hit of lead.hits) {
    const divider = hit.indexOf(':');
    const category = divider === -1 ? '命中' : hit.slice(0, divider);
    const terms = grouped.get(category) ?? [];
    terms.push(hit.slice(divider + 1));
    grouped.set(category, terms);
  }
  const items = Array.from(grouped, ([category, terms]) => node('li', `${labels[category] ?? category} · ${terms.join('、')}`));
  const empty = lead.score === null ? '待评分' : '未命中已配置的词条或偏好。';
  byId('detail-hits').replaceChildren(...(items.length ? items : [node('li', empty)]));
}

function renderLeads() {
  const query = normalized(search.value);
  const matches = leads.filter((lead) => (!pool.checked || lead.selected)
    && semantic.matches(lead) && (normalized(lead.username).includes(query) || normalized(lead.bio).includes(query)));
  const pages = Math.max(1, Math.ceil(matches.length / pageSize));
  currentPage = Math.min(currentPage, pages - 1);
  const selected = leads.filter((lead) => lead.selected).length;
  const awaitingScore = leads.filter(lead => lead.score === null || (rules && lead.rule_version !== rules.version)).length;
  byId('lead-count').textContent = `${leads.length} 条线索 · ${selected} 条入池 · ${matches.length} 条匹配`;
  byId('paging').hidden = pages === 1;
  byId('previous').disabled = currentPage === 0;
  byId('next').disabled = currentPage === pages - 1;
  byId('page-number').textContent = `第 ${currentPage + 1} / ${pages} 页`;
  leadView.value = {
    metrics: [leads.length, selected, awaitingScore],
    items: matches.slice(currentPage * pageSize, (currentPage + 1) * pageSize).map(lead => ({lead, analysis: semantic.rowState(lead)})),
    empty: leads.length ? '没有匹配的线索。' : '数据库中暂无线索。'
  };
}

async function reloadLeads() {
  leads = await api('leads');
  await semantic.loadSummaries();
  renderLeads();
}

function openLead(lead) {
  activeLead = lead.id;
  byId('detail-title').textContent = lead.username;
  byId('detail-bio').textContent = lead.bio || '无简介';
  byId('detail-score').textContent = `${lead.region || '—'} / ${lead.language || '—'} · ${scoreLabel(lead)} · ${selectionLabel(lead)}`;
  renderScoreHits(lead);
  const sources = document.createDocumentFragment();
  for (const value of lead.sources) {
    const item = node('li');
    sources.append(item);
    let url;
    try { url = new URL(value); } catch { /* Show legacy invalid URLs as text. */ }
    if (!url || !['http:', 'https:'].includes(url.protocol) || url.username || url.password) {
      item.textContent = value;
      continue;
    }
    const link = node('a', value);
    link.href = url.href;
    link.target = '_blank';
    link.rel = 'noopener noreferrer';
    item.append(link);
  }
  byId('detail-sources').replaceChildren(sources);
  byId('lead-decision').value = lead.manual_decision || 'AUTO';
  show('decision-error');
  contactForm.reset();
  detailDialog.showModal();
  void loadContact();
  void semantic.showLead(lead.id);
}

function renderContact() {
  contactForm.hidden = contact !== null;
  byId('contact-actions').hidden = !contact;
  if (contact) renderContactButtons();
  const sharedConflict = contact?.authorized && contact.effectiveStatus === 'NO_CONSENT'
    ? '（同一标识的授权记录未全部通过）' : '';
  show('contact-state', contact ? `${contact.contact} · ${reasonLabel(contact.effectiveStatus)}${sharedConflict}` : '尚未配置合成联系人。');
}

async function loadContact() {
  const request = ++contactRequest;
  contact = undefined;
  contactForm.hidden = true;
  byId('contact-actions').hidden = true;
  byId('reload-contact').hidden = true;
  show('contact-error');
  show('contact-state', '正在读取联系人…');
  try {
    const next = await api(`contacts/${activeLead}`);
    if (request !== contactRequest) return;
    contact = next;
  } catch (error) {
    if (request !== contactRequest) return;
    if (error.status !== 404) {
      show('contact-state');
      show('contact-error', error.message);
      byId('reload-contact').hidden = false;
      return;
    }
    contact = null;
  }
  renderContact();
}

function saveContact(method, input) {
  if (mutating) return;
  const request = ++contactRequest;
  void mutate('contact-error', async () => {
    show('contact-state', '正在更新联系授权…');
    try {
      const next = await api(`contacts/${activeLead}`, method, input);
      if (request !== contactRequest) return;
      contact = next;
      contactForm.reset();
      renderContact();
    } catch (error) {
      if (request !== contactRequest) return;
      if (error.status === undefined || error.status >= 500 || [404, 409].includes(error.status)) {
        await loadContact();
      } else {
        renderContact();
      }
      throw error;
    }
  });
}

function updateButtons() {
  fileInput.disabled = mutating;
  importButton.disabled = mutating || !fileInput.files.length;
  byId('score').disabled = mutating || !leads.length;
  byId('open-rules').disabled = mutating;
  byId('reload-leads').disabled = mutating;
  byId('reload-contact').disabled = mutating;
  if (!byId('contact-actions').hidden) renderContactButtons();
  for (const button of document.querySelectorAll('dialog button.primary')) button.disabled = mutating;
}

function renderContactButtons() {
  byId('authorize-contact').disabled = mutating || contact.authorized
    || ['UNSUBSCRIBED', 'BLACKLISTED'].includes(contact.effectiveStatus);
  byId('unsubscribe-contact').disabled = mutating || contact.leadUnsubscribed;
}

async function mutate(errorId, operation) {
  if (mutating) return;
  mutating = true;
  updateButtons();
  show(errorId);
  try {
    await operation();
  } catch (error) {
    show(errorId, error.message);
  } finally {
    mutating = false;
    updateButtons();
  }
}

async function refreshAfterWrite() {
  try {
    await reloadLeads();
    show('record-error');
  } catch (error) {
    show('record-error', `数据已保存，但列表刷新失败。${error.message}`);
  }
}

fileInput.addEventListener('change', () => {
  show('import-status');
  show('import-error');
  updateButtons();
});

importForm.addEventListener('submit', (event) => {
  event.preventDefault();
  const file = fileInput.files[0];
  if (!file) return;
  void mutate('import-error', async () => {
    show('import-status', '正在校验并导入文件…');
    try {
      if (file.size > 10 * 1024 * 1024) throw new Error('文件最大为 10 MB，请拆分后重试。');
      const bytes = await file.arrayBuffer();
      let text;
      try {
        text = new TextDecoder('utf-8', {fatal: true}).decode(bytes);
      } catch {
        throw new Error('文件不是 UTF-8。请在 Excel 中另存为 CSV UTF-8，或重新导出 JSON。');
      }
      const result = await api('leads/import', 'POST', parseLeadFile(file.name, text));
      importForm.reset();
      search.value = '';
      pool.checked = false;
      currentPage = 0;
      show('import-status', `已导入 ${result.imported} 条，新增 ${result.created} 条，匹配已有 ${result.matched} 条。`);
      await refreshAfterWrite();
    } catch (error) {
      show('import-status');
      throw error;
    }
  });
});

search.addEventListener('input', () => { currentPage = 0; renderLeads(); });
pool.addEventListener('change', () => { currentPage = 0; renderLeads(); });
byId('previous').addEventListener('click', () => { currentPage -= 1; renderLeads(); });
byId('next').addEventListener('click', () => { currentPage += 1; renderLeads(); });
byId('reload-contact').addEventListener('click', loadContact);
detailDialog.addEventListener('close', () => { if (!detailDialog.open) contactRequest++; });
contactForm.addEventListener('submit', (event) => {
  event.preventDefault();
  if (mutating) return;
  const fields = contactForm.elements;
  saveContact('PUT', {contactKey: fields.contactKey.value, mockScenario: fields.mockScenario.value,
    authorized: fields.authorized.checked, unsubscribed: false, blacklisted: false});
});
byId('authorize-contact').addEventListener('click', () => saveContact('PATCH', {action: 'AUTHORIZE'}));
byId('unsubscribe-contact').addEventListener('click', () => {
  if (confirm('确认退订？该线索及当前联系标识将被拦截。')) saveContact('PATCH', {action: 'UNSUBSCRIBE'});
});

byId('score').addEventListener('click', () => void mutate('record-error', async () => {
  show('record-status', '正在评分…');
  try {
    const result = await api('leads/score', 'POST');
    show('record-status', `已评分 ${result.scored} 条，规则版本 ${result.rule_version}。`);
    await refreshAfterWrite();
  } catch (error) {
    show('record-status');
    throw error;
  }
}));

byId('open-rules').addEventListener('click', () => void mutate('record-error', async () => {
  rules = await api('rules');
  for (const field of ruleFields) ruleForm.elements[field].value = rules[field].join('、');
  ruleForm.elements.threshold.value = rules.threshold;
  show('rule-error');
  byId('rule-details').showModal();
}));

ruleForm.addEventListener('submit', (event) => {
  if (event.submitter?.formMethod === 'dialog') return;
  event.preventDefault();
  void mutate('rule-error', async () => {
    const input = Object.fromEntries(ruleFields.map((field) => [field, ruleForm.elements[field].value.split(/[、,，\n]+/).map((term) => term.trim()).filter(Boolean)]));
    input.threshold = ruleForm.elements.threshold.valueAsNumber;
    rules = await api('rules', 'PUT', input);
    byId('rule-details').close();
    show('record-status', `评分规则 v${rules.version} 已保存。可重新评分更新目标池。`);
    await refreshAfterWrite();
  });
});

byId('decision-form').addEventListener('submit', (event) => {
  event.preventDefault();
  void mutate('decision-error', async () => {
    await api(`leads/${activeLead}/decision`, 'PUT', {decision: byId('lead-decision').value});
    byId('lead-details').close();
    show('record-status', '目标池决定已保存。');
    await refreshAfterWrite();
  });
});

function loadWorkbench() {
  void mutate('record-error', async () => {
    show('record-status', '正在加载工作台…');
    try {
      [leads, rules] = await Promise.all([api('leads'), api('rules')]);
      await semantic.refresh();
      byId('reload-leads').hidden = true;
      renderLeads();
    } catch (error) {
      byId('reload-leads').hidden = false;
      if (!leadView.value.metrics) leadView.value = {items: [], empty: '暂时无法读取线索，请重新加载。'};
      throw error;
    } finally {
      show('record-status');
    }
  });
}
byId('reload-leads').addEventListener('click', loadWorkbench);
loadWorkbench();

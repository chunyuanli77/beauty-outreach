import {api, node, show} from './workbench.js';

const templates = document.getElementById('template-list');
const templateForm = document.getElementById('template-form');
const frequencyForm = document.getElementById('frequency-form');
const frequencyFields = ['globalLimit', 'windowSeconds', 'cooldownSeconds', 'retrySeconds'];

async function loadTemplates() {
  const list = await api('templates');
  templates.replaceChildren(...list.map(template => {
    const item = node('div');
    item.className = 'stack';
    const body = node('div', template.body);
    body.className = 'workbench-preview';
    item.append(node('h3', template.name), body);
    return item;
  }));
  if (!list.length) templates.append(node('p', '暂时没有模板。'));
  show('templates-error');
}

function setFrequency(rules) {
  for (const field of frequencyFields) frequencyForm.elements[field].value = rules[field];
}

templateForm.addEventListener('submit', async event => {
  event.preventDefault();
  const button = templateForm.querySelector('button');
  if (button.disabled) return;
  button.disabled = true;
  show('template-error');
  show('template-status');
  try {
    const name = templateForm.elements.name.value.trim();
    const body = templateForm.elements.body.value.trim();
    if (!name || !body) throw new Error('请输入模板名称和问候文案。');
    await api('templates', 'POST', {name, body});
    templateForm.reset();
    show('template-status', '模板已保存。');
    await loadTemplates().catch(error => show('templates-error', error.message));
  } catch (error) {
    show('template-error', error.message);
  } finally {
    button.disabled = false;
  }
});

frequencyForm.addEventListener('submit', async event => {
  event.preventDefault();
  const button = frequencyForm.querySelector('button');
  if (button.disabled) return;
  button.disabled = true;
  show('frequency-error');
  show('frequency-status');
  try {
    const rules = Object.fromEntries(frequencyFields.map(field => [field, frequencyForm.elements[field].valueAsNumber]));
    setFrequency(await api('frequency', 'PUT', rules));
    show('frequency-status', '频控已保存。');
  } catch (error) {
    show('frequency-error', error.message);
  } finally {
    button.disabled = false;
  }
});

void loadTemplates().catch(error => show('templates-error', error.message));
void api('frequency').then(rules => {
  setFrequency(rules);
  frequencyForm.querySelector('button').disabled = false;
}).catch(error => show('frequency-error', error.message));

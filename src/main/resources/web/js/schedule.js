import { request } from './api.js';
import { showMessage } from './ui.js';

const rulesContainer = document.getElementById('rules');
const template = document.getElementById('rule-template');
const message = document.getElementById('schedule-message');
const timezoneLabel = document.getElementById('timezone');

const WEEKDAYS = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'];
const NEW_RULE = { enabled: true, days: WEEKDAYS.slice(0, 5), on: '18:00', off: '23:00' };

const saveButton = document.getElementById('save-rules');

let rules = [];

function setDirty(dirty) {
  saveButton.disabled = !dirty;
}

function renderRule(rule, index) {
  const element = template.content.firstElementChild.cloneNode(true);
  const on = element.querySelector('.rule-on');
  const off = element.querySelector('.rule-off');
  const enabled = element.querySelector('.rule-enabled');

  element.classList.toggle('disabled', !rule.enabled);
  on.value = rule.on;
  off.value = rule.off;
  enabled.checked = rule.enabled;

  on.addEventListener('change', () => { rule.on = on.value; setDirty(true); });
  off.addEventListener('change', () => { rule.off = off.value; setDirty(true); });
  enabled.addEventListener('change', () => {
    rule.enabled = enabled.checked;
    element.classList.toggle('disabled', !rule.enabled);
    setDirty(true);
  });

  element.querySelectorAll('.day input').forEach(checkbox => {
    checkbox.checked = rule.days.includes(checkbox.value);
    checkbox.addEventListener('change', () => {
      rule.days = WEEKDAYS.filter(day =>
        day === checkbox.value ? checkbox.checked : rule.days.includes(day));
      setDirty(true);
    });
  });

  element.querySelector('.rule-delete').addEventListener('click', () => {
    rules.splice(index, 1);
    setDirty(true);
    render();
    save('Schedule deleted');
  });
  return element;
}

function render() {
  rulesContainer.replaceChildren();
  if (rules.length === 0) {
    const empty = document.createElement('p');
    empty.className = 'empty';
    empty.textContent = 'No schedules yet.';
    rulesContainer.append(empty);
    return;
  }
  rules.forEach((rule, index) => rulesContainer.append(renderRule(rule, index)));
}

async function save(successMessage = 'Schedule saved') {
  showMessage(message, '');
  try {
    const data = await request('/api/schedules', 'PUT', rules);
    rules = data.schedules;
    setDirty(false);
    render();
    showMessage(message, successMessage, 'ok');
  } catch (error) {
    showMessage(message, error.message);
  }
}

export async function initSchedule() {
  document.getElementById('add-rule').addEventListener('click', () => {
    rules.push({ ...NEW_RULE, days: [...NEW_RULE.days] });
    setDirty(true);
    render();
  });
  saveButton.addEventListener('click', () => save());
  setDirty(false);

  try {
    const data = await request('/api/schedules');
    rules = data.schedules;
    timezoneLabel.textContent = `Times use the Pi's timezone: ${data.timezone}`;
    render();
  } catch (error) {
    showMessage(message, error.message);
  }
}

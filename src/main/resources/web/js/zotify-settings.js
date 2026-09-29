import { request } from './api.js';
import { showMessage } from './ui.js';

const container = document.getElementById('zotify-settings');
const message = document.getElementById('zotify-message');

async function save(option, select, previous) {
  select.disabled = true;
  showMessage(message, 'Saving...', 'pending');
  try {
    await request('/api/music/settings', 'PUT', { [option.id]: select.value });
    showMessage(message, `${option.label} saved`, 'ok');
  } catch (error) {
    select.value = previous;
    showMessage(message, `Could not save ${option.label}: ${error.message}`);
  } finally {
    select.disabled = false;
  }
}

function renderOption(option) {
  const label = document.createElement('label');
  label.className = 'select-setting';

  const text = document.createElement('span');
  const title = document.createElement('strong');
  title.textContent = option.label;
  text.append(title);
  if (option.help) {
    const help = document.createElement('small');
    help.textContent = option.help;
    text.append(help);
  }

  const select = document.createElement('select');
  option.choices.forEach(choice => {
    const item = document.createElement('option');
    item.value = choice.value;
    item.textContent = choice.label;
    select.append(item);
  });
  select.value = option.value;
  let current = select.value;
  select.addEventListener('change', async () => {
    await save(option, select, current);
    current = select.value;
  });

  label.append(text, select);
  return label;
}

function render(options) {
  container.replaceChildren();
  const groups = new Map();
  options.forEach(option => {
    if (!groups.has(option.group)) {
      const card = document.createElement('section');
      card.className = 'card';
      const heading = document.createElement('h2');
      heading.textContent = option.group;
      card.append(heading);
      groups.set(option.group, card);
      container.append(card);
    }
    groups.get(option.group).append(renderOption(option));
  });
}

export async function initZotifySettings() {
  try {
    render((await request('/api/music/settings')).options);
  } catch (error) {
    showMessage(message, error.message);
  }
}

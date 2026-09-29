import { request } from './api.js';
import { showMessage } from './ui.js';

const button = document.getElementById('lamp');
const message = document.getElementById('lamp-message');

let isOn = false;
let hintShown = false;
const HINT = 'USB power is on because the drive is open, so the lamp is lit';

function render() {
  button.classList.toggle('on', isOn);
  button.setAttribute('aria-pressed', String(isOn));
  button.textContent = isOn ? 'ON' : 'OFF';
}

async function toggle() {
  const word = isOn ? 'off' : 'on';
  button.disabled = true;
  showMessage(message, `Turning ${word}...`, 'pending');
  try {
    const data = await request(`/api/${word}`, 'POST');
    isOn = data.on;
    render();
    showMessage(message, `Lamp turned ${word}`, 'ok');
    await syncState();
  } catch (error) {
    await syncState();
    showMessage(message, `Could not turn ${word}: ${error.message}`);
  } finally {
    button.disabled = false;
  }
}

/** Re-reads the real lamp state; returns false if it could not be read. */
export async function syncState() {
  try {
    const data = await request('/api/status');
    isOn = data.on;
    render();
    if (data.usbPower && !isOn) {
      showMessage(message, HINT, 'pending');
      hintShown = true;
    } else if (hintShown) {
      showMessage(message, '');
      hintShown = false;
    }
    return true;
  } catch (error) {
    showMessage(message, error.message);
    return false;
  }
}

export async function initLamp() {
  button.addEventListener('click', toggle);
  await syncState();
  button.disabled = false;
}

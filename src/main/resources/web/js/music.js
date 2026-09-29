import { request } from './api.js';
import { showMessage } from './ui.js';
import { syncState as syncLamp } from './lamp.js';
import { syncDrive } from './drive.js';
import { onSettingsChange } from './settings.js';

const input = document.getElementById('music-url');
const serverButton = document.getElementById('music-server');
const deviceButton = document.getElementById('music-device');
const message = document.getElementById('music-message');
const saveLink = document.getElementById('music-save');

const POLL_MS = 1500;
const MAX_POLL_FAILURES = 5;

function setBusy(busy) {
  serverButton.disabled = busy;
  deviceButton.disabled = busy;
  input.disabled = busy;
}

function updateServerButtonState(driveDisconnected) {
  serverButton.disabled = driveDisconnected;
  if (driveDisconnected) {
    serverButton.title = 'Disabled: Drive is disconnected';
  } else {
    serverButton.title = '';
  }
}

function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

function describe(job) {
  return job.progress ? `${job.message} ${job.progress}` : job.message;
}

/** Starts the browser download of a finished "to device" job. */
function saveToDevice(job) {
  const url = `/api/music/jobs/${job.id}/file`;
  saveLink.href = url;
  saveLink.hidden = false;
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = job.fileName;
  document.body.append(anchor);
  anchor.click();
  anchor.remove();
}

async function follow(job) {
  let failures = 0;
  while (job.state === 'RUNNING') {
    showMessage(message, describe(job), 'pending');
    await sleep(POLL_MS);
    try {
      job = await request(`/api/music/jobs/${job.id}`);
      failures = 0;
    } catch (error) {
      if (++failures >= MAX_POLL_FAILURES) {
        throw error;
      }
    }
  }
  if (job.state === 'FAILED') {
    showMessage(message, job.message);
  } else if (job.target === 'DEVICE') {
    showMessage(message, `Downloaded ${job.fileName} - saving to this device`, 'ok');
    saveToDevice(job);
  } else {
    showMessage(message, job.message, 'ok');
  }
}

async function run(startJob) {
  saveLink.hidden = true;
  setBusy(true);
  try {
    await follow(await startJob());
  } catch (error) {
    showMessage(message, error.message);
  } finally {
    setBusy(false);
    await syncDrive();
    await syncLamp();
  }
}

function start(target) {
  const url = input.value.trim();
  if (!url) {
    showMessage(message, 'Paste a Spotify link first');
    input.focus();
    return;
  }
  showMessage(message, 'Starting...', 'pending');
  run(() => request('/api/music/download', 'POST', { url, target }));
}

export async function initMusic() {
  serverButton.addEventListener('click', () => start('server'));
  deviceButton.addEventListener('click', () => start('device'));
  
  onSettingsChange(settings => {
    updateServerButtonState(settings.driveDisconnected);
  });
  
  try {
    const settings = await request('/api/settings');
    updateServerButtonState(settings.driveDisconnected);
  } catch {
    // continue without checking drive disconnect state
  }
  try {
    const { job } = await request('/api/music/current');
    if (job) {
      input.value = job.url;
      run(async () => job);
    }
  } catch {
    // the message area is only for actions the user starts
  }
}

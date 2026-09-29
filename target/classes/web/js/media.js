import { request } from './api.js';
import { formatSize, joinPath, showMessage } from './ui.js';
import { syncState as syncLamp } from './lamp.js';
import { syncDrive } from './drive.js';

const panel = document.getElementById('media-panel');
const statusLabel = document.getElementById('media-status');
const openButton = document.getElementById('media-open');
const message = document.getElementById('media-message');
const library = document.getElementById('media-library');
const breadcrumb = document.getElementById('media-breadcrumb');
const list = document.getElementById('media-list');
const playerCard = document.getElementById('media-player-card');
const nowPlaying = document.getElementById('media-now-playing');
const player = document.getElementById('media-player');
const closeButton = document.getElementById('media-close');

const UNSUPPORTED = 'Your browser cannot play this file. MP4 (H.264/AAC) plays everywhere; '
  + 'MKV and HEVC only work in some browsers.';

let currentPath = '/';
let rootLabel = 'Streaming';
let playingPath = null;

const streamUrl = path => `/api/stream/play?path=${encodeURIComponent(path)}`;

function markPlaying() {
  [...list.children].forEach(item => item.classList.toggle('playing', item.dataset.path === playingPath));
}

function play(name, path) {
  playingPath = path;
  nowPlaying.textContent = name;
  playerCard.hidden = false;
  player.src = streamUrl(path);
  // Browsers may block autoplay; the controls are still there
  player.play().catch(() => {});
  playerCard.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
  showMessage(message, '');
  markPlaying();
}

/** Stops playback and drops the connection so the drive is no longer held busy. */
function closePlayer() {
  playingPath = null;
  player.pause();
  player.removeAttribute('src');
  player.load();
  playerCard.hidden = true;
  markPlaying();
}

function renderBreadcrumb() {
  breadcrumb.replaceChildren();
  const parts = currentPath.split('/').filter(Boolean);
  let path = '';
  [[rootLabel, '/'], ...parts.map(part => [part, path += `/${part}`])].forEach(([label, target]) => {
    const crumb = document.createElement('button');
    crumb.type = 'button';
    crumb.className = 'crumb';
    crumb.textContent = label;
    crumb.addEventListener('click', () => browse(target));
    breadcrumb.append(crumb);
  });
}

function renderEntry(entry) {
  const path = joinPath(currentPath, entry.name);
  const item = document.createElement('li');
  item.className = 'file';
  item.dataset.path = path;

  const name = document.createElement('button');
  name.type = 'button';
  name.className = 'file-name' + (entry.dir ? ' dir' : '');
  name.textContent = (entry.dir ? '📁 ' : '🎬 ') + entry.name;
  name.addEventListener('click', () => (entry.dir ? browse(path) : play(entry.name, path)));

  const meta = document.createElement('span');
  meta.className = 'file-meta';
  meta.textContent = entry.dir ? '' : formatSize(entry.size);

  item.append(name, meta);
  return item;
}

async function browse(path) {
  try {
    const data = await request(`/api/stream/list?path=${encodeURIComponent(path)}`);
    currentPath = data.path;
    rootLabel = data.folder;
    renderBreadcrumb();
    list.replaceChildren();
    if (data.entries.length === 0) {
      const empty = document.createElement('li');
      empty.className = 'empty';
      empty.textContent = 'No videos in this folder (mp4, m4v, mov, webm, mkv).';
      list.append(empty);
    }
    data.entries.forEach(entry => list.append(renderEntry(entry)));
    markPlaying();
    showMessage(message, '');
  } catch (error) {
    list.replaceChildren();
    breadcrumb.replaceChildren();
    showMessage(message, error.message);
  }
}

/** Shows the drive state and, when it is open, the current folder. */
async function refresh() {
  const status = await request('/api/drive/status');
  const open = status.state === 'OPEN';
  const busy = status.state === 'OPENING' || status.state === 'CLOSING';
  openButton.hidden = open;
  openButton.disabled = busy;
  library.hidden = !open;
  if (open) {
    statusLabel.textContent = 'Pick a movie to start streaming it to this device.';
    await browse(currentPath);
  } else {
    statusLabel.textContent = busy ? 'Working...' : 'The drive is closed. Open it to browse your movies.';
    closePlayer();
  }
}

async function openDrive() {
  openButton.disabled = true;
  showMessage(message, 'Powering on and mounting the drive...', 'pending');
  try {
    await request('/api/drive/open', 'POST');
    showMessage(message, '');
  } catch (error) {
    showMessage(message, `Could not open the drive: ${error.message}`);
  }
  await refresh().catch(() => {});
  await syncDrive();
  await syncLamp();
}

function onPlayerError() {
  if (playingPath === null) {
    return;
  }
  const unsupported = player.error?.code === MediaError.MEDIA_ERR_SRC_NOT_SUPPORTED;
  showMessage(message, unsupported ? UNSUPPORTED : 'Playback failed. Is the drive still open?');
  if (!unsupported) {
    refresh().catch(() => {});
  }
}

export async function initMedia() {
  openButton.addEventListener('click', openDrive);
  closeButton.addEventListener('click', closePlayer);
  player.addEventListener('error', onPlayerError);

  // Stop the sound when another tab is opened; re-read the drive state when this one is
  new MutationObserver(() => {
    if (panel.hidden) {
      player.pause();
    } else {
      refresh().catch(error => showMessage(message, error.message));
    }
  }).observe(panel, { attributes: true, attributeFilter: ['hidden'] });

  try {
    await refresh();
  } catch (error) {
    showMessage(message, error.message);
  }
}

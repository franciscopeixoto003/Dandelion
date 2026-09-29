import { request } from './api.js';
import { formatSize, joinPath, showMessage } from './ui.js';
import { syncState as syncLamp } from './lamp.js';

const statusLabel = document.getElementById('drive-status');
const openButton = document.getElementById('drive-open');
const closeButton = document.getElementById('drive-close');
const message = document.getElementById('drive-message');
const files = document.getElementById('files');
const breadcrumb = document.getElementById('breadcrumb');
const list = document.getElementById('file-list');
const filesMessage = document.getElementById('files-message');
const uploadInput = document.getElementById('upload-input');
const uploadProgress = document.getElementById('upload-progress');

const HEADERS = { 'X-Requested-With': 'lamp-ui' };
let currentPath = '/';

function parentPath(path) {
  return path.slice(0, path.lastIndexOf('/')) || '/';
}

function downloadUrl(path, download) {
  return `/api/files/download?path=${encodeURIComponent(path)}${download ? '&download=1' : ''}`;
}

function renderStatus(status) {
  const open = status.state === 'OPEN';
  const busy = status.state === 'OPENING' || status.state === 'CLOSING';
  openButton.hidden = open;
  closeButton.hidden = !open;
  openButton.disabled = busy;
  closeButton.disabled = busy;
  files.hidden = !open;
  if (open) {
    const space = status.totalBytes
      ? ` - ${formatSize(status.freeBytes)} free of ${formatSize(status.totalBytes)}`
      : '';
    const idle = status.idleMinutes > 0 ? ` Ejects after ${status.idleMinutes} min without file activity.` : '';
    statusLabel.textContent = `Open${space}.${idle}`;
  } else {
    statusLabel.textContent = busy ? 'Working...' : 'Closed - USB power is off unless the lamp needs it';
    list.replaceChildren();
  }
}

async function refreshStatus() {
  const status = await request('/api/drive/status');
  renderStatus(status);
  return status;
}

async function openDrive() {
  openButton.disabled = true;
  showMessage(message, 'Powering on and mounting the drive...', 'pending');
  try {
    renderStatus(await request('/api/drive/open', 'POST'));
    showMessage(message, '');
    await browse('/');
  } catch (error) {
    showMessage(message, `Could not open the drive: ${error.message}`);
    await refreshStatus().catch(() => {});
  }
  await syncLamp();
}

async function closeDrive() {
  closeButton.disabled = true;
  showMessage(message, 'Ejecting and powering off...', 'pending');
  try {
    renderStatus(await request('/api/drive/close', 'POST'));
    showMessage(message, 'Drive ejected and USB power turned off', 'ok');
  } catch (error) {
    showMessage(message, `Could not eject: ${error.message}`);
    await refreshStatus().catch(() => {});
  }
  await syncLamp();
}

function renderBreadcrumb() {
  breadcrumb.replaceChildren();
  const parts = currentPath.split('/').filter(Boolean);
  let path = '';
  [['Drive', '/'], ...parts.map(part => [part, path += `/${part}`])].forEach(([label, target]) => {
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

  const name = document.createElement('button');
  name.type = 'button';
  name.className = 'file-name' + (entry.dir ? ' dir' : '');
  name.textContent = (entry.dir ? '📁 ' : '') + entry.name;
  name.addEventListener('click', () => {
    if (entry.dir) {
      browse(path);
    } else {
      window.open(downloadUrl(path, false), '_blank', 'noopener');
    }
  });

  const meta = document.createElement('span');
  meta.className = 'file-meta';
  meta.textContent = entry.dir ? '' : formatSize(entry.size);

  item.append(name, meta);
  if (!entry.dir) {
    const save = document.createElement('a');
    save.className = 'btn';
    save.href = downloadUrl(path, true);
    save.textContent = 'Download';
    item.append(save);
  }
  item.append(
    actionButton('Rename', () => rename(entry, path)),
    actionButton('Delete', () => remove(entry, path), 'danger'));
  return item;
}

function actionButton(label, onClick, kind = '') {
  const button = document.createElement('button');
  button.type = 'button';
  button.className = ('btn ' + kind).trim();
  button.textContent = label;
  button.addEventListener('click', onClick);
  return button;
}

async function browse(path) {
  try {
    const data = await request(`/api/files?path=${encodeURIComponent(path)}`);
    currentPath = data.path;
    renderBreadcrumb();
    list.replaceChildren();
    if (data.entries.length === 0) {
      const empty = document.createElement('li');
      empty.className = 'empty';
      empty.textContent = 'This folder is empty.';
      list.append(empty);
    }
    data.entries.forEach(entry => list.append(renderEntry(entry)));
    showMessage(filesMessage, '');
  } catch (error) {
    showMessage(filesMessage, error.message);
    await refreshStatus().catch(() => {});
  }
}

async function change(url, method, success) {
  try {
    await request(url, method);
    await browse(currentPath);
    showMessage(filesMessage, success, 'ok');
  } catch (error) {
    showMessage(filesMessage, error.message);
  }
}

function newFolder() {
  const name = prompt('Folder name');
  if (name) {
    change(`/api/files/mkdir?path=${encodeURIComponent(currentPath)}&name=${encodeURIComponent(name)}`,
      'POST', 'Folder created');
  }
}

function rename(entry, path) {
  const name = prompt('New name', entry.name);
  if (name && name !== entry.name) {
    change(`/api/files/rename?path=${encodeURIComponent(path)}&name=${encodeURIComponent(name)}`,
      'POST', 'Renamed');
  }
}

function remove(entry, path) {
  if (confirm(`Delete "${entry.name}"${entry.dir ? ' and everything in it' : ''}?`)) {
    change(`/api/files?path=${encodeURIComponent(path)}`, 'DELETE', 'Deleted');
  }
}

function uploadOne(file, index, total) {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open('POST', `/api/files/upload?path=${encodeURIComponent(currentPath)}&name=${encodeURIComponent(file.name)}`);
    xhr.setRequestHeader('X-Requested-With', HEADERS['X-Requested-With']);
    xhr.upload.onprogress = event => {
      if (event.lengthComputable) {
        uploadProgress.value = (event.loaded / event.total) * 100;
        showMessage(filesMessage, `Uploading ${file.name} (${index + 1}/${total})...`, 'pending');
      }
    };
    xhr.onload = () => {
      if (xhr.status === 200) {
        resolve();
      } else {
        let text = 'Upload failed';
        try { text = JSON.parse(xhr.responseText).error || text; } catch { /* keep default */ }
        reject(new Error(`${file.name}: ${text}`));
      }
    };
    xhr.onerror = () => reject(new Error(`${file.name}: cannot reach server`));
    xhr.send(file);
  });
}

async function upload() {
  const selected = [...uploadInput.files];
  uploadInput.value = '';
  uploadProgress.hidden = false;
  try {
    for (const [index, file] of selected.entries()) {
      await uploadOne(file, index, selected.length);
    }
    await browse(currentPath);
    showMessage(filesMessage, `Uploaded ${selected.length} file(s)`, 'ok');
  } catch (error) {
    await browse(currentPath);
    showMessage(filesMessage, error.message);
  } finally {
    uploadProgress.hidden = true;
    uploadProgress.value = 0;
  }
}

/** Re-reads the drive state, e.g. after a music download opened it. */
export async function syncDrive() {
  try {
    const status = await refreshStatus();
    openButton.disabled = status.state !== 'CLOSED';
    if (status.state === 'OPEN') {
      await browse(currentPath);
    }
  } catch {
    // the Drive tab reports its own errors
  }
}

export async function initDrive() {
  openButton.addEventListener('click', openDrive);
  closeButton.addEventListener('click', closeDrive);
  uploadInput.addEventListener('change', upload);
  document.getElementById('new-folder').addEventListener('click', newFolder);
  try {
    const status = await refreshStatus();
    openButton.disabled = status.state !== 'CLOSED';
    if (status.state === 'OPEN') {
      await browse('/');
    }
  } catch (error) {
    showMessage(message, error.message);
  }
}

import { request } from './api.js';
import { showMessage } from './ui.js';

const lampDisconnectedCheckbox = document.getElementById('allow-lamp-disconnect');
const driveDisconnectedCheckbox = document.getElementById('allow-drive-disconnect');
const drivePriorityCheckbox = document.getElementById('drive-priority');
const message = document.getElementById('settings-message');
const lampTab = document.getElementById('tab-lamp');
const driveTab = document.getElementById('tab-drive');

let settingsChangeListeners = [];

export function onSettingsChange(callback) {
  settingsChangeListeners.push(callback);
}

function notifySettingsChanged(settings) {
  settingsChangeListeners.forEach(callback => callback(settings));
}

/** Leaves a tab that just became unavailable. */
function leaveIfSelected(tab) {
  if (tab.getAttribute('aria-selected') === 'true') {
    document.querySelector('.tab:not(.ghost-tab)')?.click();
  }
}

function updateLampTabVisibility(allowed) {
  if (allowed) {
    lampTab.classList.remove('ghost-tab');
    lampTab.disabled = false;
  } else {
    lampTab.classList.add('ghost-tab');
    lampTab.disabled = true;
    leaveIfSelected(lampTab);
  }
}

function updateDriveTabVisibility(allowed) {
  driveTab.classList.toggle('ghost-tab', !allowed);
  driveTab.disabled = !allowed;
  if (!allowed) {
    leaveIfSelected(driveTab);
  }
}

function updateDrivePriorityLock(lampDisconnected, driveDisconnected) {
  // Lock and set Drive Priority based on connection status
  if (lampDisconnected) {
    // Lamp disconnected: Drive Priority MUST be ON
    drivePriorityCheckbox.checked = true;
    drivePriorityCheckbox.disabled = true;
  } else if (driveDisconnected) {
    // Drive disconnected: Drive Priority MUST be OFF
    drivePriorityCheckbox.checked = false;
    drivePriorityCheckbox.disabled = true;
  } else {
    // Both devices connected: Drive Priority can be toggled
    drivePriorityCheckbox.disabled = false;
  }
}

async function saveLampDisconnect() {
  lampDisconnectedCheckbox.disabled = true;
  const lampDisconnected = lampDisconnectedCheckbox.checked;
  const driveDisconnected = driveDisconnectedCheckbox.checked;
  
  // Enforce Drive Priority rules and UPDATE UI INSTANTLY
  if (lampDisconnected) {
    drivePriorityCheckbox.checked = true;
    drivePriorityCheckbox.disabled = true;
  } else if (driveDisconnected) {
    drivePriorityCheckbox.checked = false;
    drivePriorityCheckbox.disabled = true;
  } else {
    drivePriorityCheckbox.disabled = false;
  }
  
  let drivePriority = drivePriorityCheckbox.checked;
  
  try {
    const data = await request('/api/settings', 'PUT', { 
      lampDisconnected: lampDisconnected,
      driveDisconnected: driveDisconnected,
      drivePriority: drivePriority
    });
    lampDisconnectedCheckbox.checked = data.lampDisconnected;
    updateLampTabVisibility(!data.lampDisconnected);
    updateDrivePriorityLock(data.lampDisconnected, data.driveDisconnected);
    drivePriorityCheckbox.checked = data.drivePriority;
    showMessage(message, '');
    notifySettingsChanged(data);
  } catch (error) {
    lampDisconnectedCheckbox.checked = !lampDisconnectedCheckbox.checked;
    updateDrivePriorityLock(!lampDisconnected, driveDisconnected);
    showMessage(message, error.message);
  } finally {
    lampDisconnectedCheckbox.disabled = false;
  }
}

async function saveDriveDisconnect() {
  driveDisconnectedCheckbox.disabled = true;
  const lampDisconnected = lampDisconnectedCheckbox.checked;
  const driveDisconnected = driveDisconnectedCheckbox.checked;
  
  // Enforce Drive Priority rules and UPDATE UI INSTANTLY
  if (lampDisconnected) {
    drivePriorityCheckbox.checked = true;
    drivePriorityCheckbox.disabled = true;
  } else if (driveDisconnected) {
    drivePriorityCheckbox.checked = false;
    drivePriorityCheckbox.disabled = true;
  } else {
    drivePriorityCheckbox.disabled = false;
  }
  
  let drivePriority = drivePriorityCheckbox.checked;
  
  try {
    const data = await request('/api/settings', 'PUT', { 
      lampDisconnected: lampDisconnected,
      driveDisconnected: driveDisconnected,
      drivePriority: drivePriority
    });
    updateDrivePriorityLock(data.lampDisconnected, data.driveDisconnected);
    driveDisconnectedCheckbox.checked = data.driveDisconnected;
    drivePriorityCheckbox.checked = data.drivePriority;
    updateDriveTabVisibility(!data.driveDisconnected);
    showMessage(message, '');
    notifySettingsChanged(data);
  } catch (error) {
    driveDisconnectedCheckbox.checked = !driveDisconnectedCheckbox.checked;
    updateDrivePriorityLock(lampDisconnected, !driveDisconnected);
    showMessage(message, error.message);
  } finally {
    driveDisconnectedCheckbox.disabled = false;
  }
}

async function saveDrivePriority() {
  drivePriorityCheckbox.disabled = true;
  const lampDisconnected = lampDisconnectedCheckbox.checked;
  const driveDisconnected = driveDisconnectedCheckbox.checked;
  
  // Enforce the priority rules
  let requestedPriority = drivePriorityCheckbox.checked;
  if (lampDisconnected) {
    requestedPriority = true; // Lamp disconnected: priority MUST be ON
  } else if (driveDisconnected) {
    requestedPriority = false; // Drive disconnected: priority MUST be OFF
  }
  
  // Update UI instantly
  drivePriorityCheckbox.checked = requestedPriority;
  
  try {
    const data = await request('/api/settings', 'PUT', { 
      drivePriority: requestedPriority,
      lampDisconnected: lampDisconnected,
      driveDisconnected: driveDisconnected
    });
    drivePriorityCheckbox.checked = data.drivePriority;
    updateDrivePriorityLock(data.lampDisconnected, data.driveDisconnected);
    showMessage(message, '');
    notifySettingsChanged(data);
  } catch (error) {
    drivePriorityCheckbox.checked = !drivePriorityCheckbox.checked;
    showMessage(message, error.message);
  } finally {
    drivePriorityCheckbox.disabled = false;
  }
}

export async function initSettings() {
  lampDisconnectedCheckbox.addEventListener('change', saveLampDisconnect);
  driveDisconnectedCheckbox.addEventListener('change', saveDriveDisconnect);
  drivePriorityCheckbox.addEventListener('change', saveDrivePriority);
  try {
    const settings = await request('/api/settings');
    lampDisconnectedCheckbox.checked = settings.lampDisconnected;
    driveDisconnectedCheckbox.checked = settings.driveDisconnected;
    drivePriorityCheckbox.checked = settings.drivePriority;
    updateLampTabVisibility(!settings.lampDisconnected);
    updateDriveTabVisibility(!settings.driveDisconnected);
    updateDrivePriorityLock(settings.lampDisconnected, settings.driveDisconnected);
    lampDisconnectedCheckbox.disabled = false;
    driveDisconnectedCheckbox.disabled = false;
  } catch (error) {
    showMessage(message, error.message);
  }
}

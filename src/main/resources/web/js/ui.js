/** Shows a status message; kind is '', 'ok' or 'pending'. */
export function showMessage(element, text, kind = '') {
  element.textContent = text;
  element.className = ('message ' + kind).trim();
}

export function formatSize(bytes) {
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let value = bytes;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit++;
  }
  return `${unit === 0 ? value : value.toFixed(1)} ${units[unit]}`;
}

export function joinPath(dir, name) {
  return dir === '/' ? `/${name}` : `${dir}/${name}`;
}

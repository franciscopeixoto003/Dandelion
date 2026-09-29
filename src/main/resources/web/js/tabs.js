const tabs = [...document.querySelectorAll('.tab')];
const subtabs = [...document.querySelectorAll('.subtab')];

const idOf = tab => tab.id.replace(/^(sub)?tab-/, '');
const isSelected = tab => tab.getAttribute('aria-selected') === 'true';

function updateHash() {
  const main = tabs.find(isSelected);
  const sub = subtabs.find(isSelected);
  history.replaceState(null, '', '#' + idOf(main) + (main.id === 'tab-settings' ? '/' + idOf(sub) : ''));
}

/** Makes a row of tab buttons show one panel at a time; returns the select function. */
function setupTabs(group, keys) {
  function select(tab, focus = false) {
    group.forEach(t => {
      const selected = t === tab;
      t.setAttribute('aria-selected', String(selected));
      t.tabIndex = selected ? 0 : -1;
      document.getElementById(t.getAttribute('aria-controls')).hidden = !selected;
    });
    updateHash();
    if (focus) {
      tab.focus();
    }
  }
  group.forEach(tab => {
    tab.addEventListener('click', (event) => {
      if (!tab.classList.contains('ghost-tab')) {
        select(tab);
      }
    });
    tab.addEventListener('keydown', event => {
      const step = keys[event.key];
      if (step && !event.altKey) {
        // Tabs can be reordered, so navigate in the order they are shown
        const shown = group.filter(t => !t.classList.contains('ghost-tab')).toSorted((a, b) => (a.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING ? -1 : 1));
        event.preventDefault();
        select(shown[(shown.indexOf(tab) + step + shown.length) % shown.length], true);
      }
    });
  });
  return select;
}

export function initTabs() {
  const selectMain = setupTabs(tabs, { ArrowDown: 1, ArrowRight: 1, ArrowUp: -1, ArrowLeft: -1 });
  const selectSub = setupTabs(subtabs, { ArrowRight: 1, ArrowLeft: -1 });

  // Also runs when a link such as href="#settings/zotify" is followed
  function applyHash() {
    const [mainId, subId] = location.hash.slice(1).split('/');
    const sub = subtabs.find(t => idOf(t) === subId);
    const main = tabs.find(t => idOf(t) === mainId);
    if (sub) {
      selectSub(sub);
    }
    if (main && !main.classList.contains('ghost-tab')) {
      selectMain(main);
    }
  }
  window.addEventListener('hashchange', applyHash);
  applyHash();
}

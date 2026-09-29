const STORAGE_KEY = 'tabOrder';
const DRAG_THRESHOLD = 6;
const KEY_STEPS = { ArrowUp: -1, ArrowLeft: -1, ArrowDown: 1, ArrowRight: 1 };

const sidebar = document.querySelector('.sidebar');
const pinnedTabs = [...sidebar.querySelectorAll('.tab[data-pinned]')];
const movable = () => [...sidebar.querySelectorAll('.tab:not([data-pinned])')];
const idOf = tab => tab.id.replace('tab-', '');

let justDragged = false;

function save() {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(movable().map(idOf)));
  } catch {
    // storage unavailable (private mode): the order just will not persist
  }
}

function restore() {
  let saved = [];
  try {
    saved = JSON.parse(localStorage.getItem(STORAGE_KEY)) || [];
  } catch {
    return;
  }
  const rank = tab => (saved.includes(idOf(tab)) ? saved.indexOf(idOf(tab)) : saved.length);
  const tabs = movable().sort((a, b) => rank(a) - rank(b));
  const insertBefore = pinnedTabs[1] || null;
  tabs.forEach(tab => sidebar.insertBefore(tab, insertBefore));
}

/** Moves the tab to where the pointer is; movable tabs stay between pinned tabs. */
function reposition(tab, pointer, horizontal) {
  const before = movable().filter(t => t !== tab).find(other => {
    const box = other.getBoundingClientRect();
    return pointer < (horizontal ? box.left + box.width / 2 : box.top + box.height / 2);
  });
  const target = before ?? (pinnedTabs[1] || null);
  if (tab.nextElementSibling !== target) {
    sidebar.insertBefore(tab, target);
  }
}

function startDrag(event, tab) {
  if (event.button !== 0 || tab.classList.contains('ghost-tab')) {
    return;
  }
  const startX = event.clientX;
  const startY = event.clientY;
  let dragging = false;

  // Listen on the window: moving the tab in the DOM makes the browser drop its pointer capture
  function move(e) {
    if (e.pointerId !== event.pointerId) {
      return;
    }
    if (!dragging && Math.hypot(e.clientX - startX, e.clientY - startY) < DRAG_THRESHOLD) {
      return;
    }
    dragging = true;
    tab.classList.add('dragging');
    const horizontal = getComputedStyle(sidebar).flexDirection === 'row';
    reposition(tab, horizontal ? e.clientX : e.clientY, horizontal);
  }

  function end() {
    window.removeEventListener('pointermove', move);
    window.removeEventListener('pointerup', end);
    window.removeEventListener('pointercancel', end);
    tab.classList.remove('dragging');
    if (dragging) {
      justDragged = true;
      setTimeout(() => { justDragged = false; }, 0);
      save();
    }
  }

  window.addEventListener('pointermove', move);
  window.addEventListener('pointerup', end);
  window.addEventListener('pointercancel', end);
}

/** Alt + arrow keys move the focused tab, for keyboard users. */
function keyMove(event, tab) {
  const step = KEY_STEPS[event.key];
  if (!step || !event.altKey) {
    return;
  }
  event.preventDefault();
  event.stopImmediatePropagation();
  const tabs = movable();
  const next = tabs[tabs.indexOf(tab) + step];
  if (next) {
    sidebar.insertBefore(tab, step < 0 ? next : next.nextElementSibling);
    tab.focus();
    save();
  }
}

/** Lets the side menu tabs be dragged into a new order, remembered in this browser. */
export function initTabOrder() {
  restore();
  movable().forEach(tab => {
    tab.title = 'Drag to reorder (or Alt + arrow keys)';
    tab.addEventListener('pointerdown', event => startDrag(event, tab));
    tab.addEventListener('keydown', event => keyMove(event, tab));
    // Registered before the tab switching click handler, so a drop does not also open the tab
    tab.addEventListener('click', event => {
      if (justDragged) {
        event.stopImmediatePropagation();
      }
      if (tab.classList.contains('ghost-tab')) {
        event.preventDefault();
        event.stopImmediatePropagation();
      }
    });
  });
}

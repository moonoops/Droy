// Inlined into the mobile plugin. DSH-specific operations enter through callbacks.
function createTouchController(target, options = {}) {
  const doc = target.document;
  const rows = new Map();
  const menus = new Map();
  const menuAnimations = new Map();
  const menuOrder = new Map();
  let menuSequence = 0;
  const pointers = new Set();
  const systemPointers = new Set();
  const listeners = [];
  const touchSurfaces = new Set();
  let active = null;
  let expanded = null;
  let consumedClick = false;
  let lastInput = 'touch';
  let disposed = false;
  let feedback;
  let feedbackTimer;
  const previousInputMode = doc.documentElement.getAttribute?.('data-droy-input');
  const inputMode = value => {
    lastInput = value;
    doc.documentElement.setAttribute?.('data-droy-input', value);
  };
  inputMode(lastInput);
  const now = () => target.performance?.now?.() ?? Date.now();
  const config = () => target.__droyTouchConfig || {};
  const slop = () => Math.max(4, Number(config().touchSlopCssPx) || 8);
  const systemEdge = x => {
    const width = Number(target.innerWidth) || Number(doc.documentElement.clientWidth) || 0;
    const left = Math.max(0, Number(config().systemGestureLeftCssPx) || 0);
    const right = Math.max(0, Number(config().systemGestureRightCssPx) || 0);
    return left > 0 && x <= left || width > 0 && right > 0 && x >= width - right;
  };
  const editable = node => !!node?.closest?.('input, textarea, [contenteditable]:not([contenteditable="false"])');
  const interactive = node => !!node?.closest?.('button, a, input, textarea, select, [contenteditable="true"], [role="button"], [role="menuitem"]');
  const listen = (node, type, handler, settings) => {
    if (!node?.addEventListener) return;
    node.addEventListener(type, handler, settings);
    listeners.push(() => node.removeEventListener(type, handler, settings));
  };
  const block = event => { event.preventDefault?.(); event.stopImmediatePropagation?.(); event.stopPropagation?.(); };
  const touchMoveOptions = { capture: true, passive: false };
  const horizontalTouchMove = event => {
    // Android WebView can swallow the next tap after native swipe processing.
    // Cancel only the horizontal sequence we own; vertical scrolling stays native.
    if (active?.lock === 'horizontal') event.preventDefault();
  };
  function syncTouchSurfaces() {
    const current = new Set(['[data-droy-sidebar]', '[data-droy-backdrop]']
      .map(selector => doc.querySelector?.(selector))
      .filter(node => node?.addEventListener && node.isConnected !== false));
    for (const node of touchSurfaces) if (!current.has(node)) {
      node.removeEventListener('touchmove', horizontalTouchMove, touchMoveOptions);
      touchSurfaces.delete(node);
    }
    for (const node of current) if (!touchSurfaces.has(node)) {
      node.addEventListener('touchmove', horizontalTouchMove, touchMoveOptions);
      touchSurfaces.add(node);
    }
  }
  const drawerOpen = () => options.isDrawerOpen?.() ?? !!doc.querySelector?.('[data-droy-drawer-open="true"]');
  const hasModal = () => [...menus.values()].some(menu => menu.isOpen?.()) || (options.hasModal?.()
    ?? Array.from(doc.querySelectorAll?.('[aria-modal="true"], [role="menu"]') || [])
      .some(node => !node.hasAttribute?.('data-droy-sidebar')));
  const rowFor = node => {
    const element = node?.closest?.('[data-droy-session-row]');
    return element && rows.get(element);
  };
  const actionWidth = entry => entry.element.querySelector?.('[data-droy-swipe-action]')?.getBoundingClientRect?.().width
    || Math.max(72, 72 * (Number(config().dpToCss) || 1));
  function paintRow(entry, offset, dragging = false) {
    entry.element.style?.setProperty('--droy-row-offset', `${offset}px`);
    if (dragging) entry.element.setAttribute('data-droy-row-dragging', 'true');
    else entry.element.removeAttribute('data-droy-row-dragging');
  }
  function setExpanded(entry, value) {
    if (value && expanded && expanded !== entry) setExpanded(expanded, false);
    if (value) {
      expanded = entry;
      entry.element.setAttribute('data-droy-row-expanded', 'true');
    } else {
      if (expanded === entry) expanded = null;
      entry.element.removeAttribute('data-droy-row-expanded');
    }
    const action = entry.element.querySelector?.('[data-droy-swipe-action]');
    if (action) {
      action.inert = !value;
      if (value) action.removeAttribute('inert');
      else action.setAttribute('inert', '');
      action.setAttribute('aria-hidden', String(!value));
    }
    paintRow(entry, value ? -actionWidth(entry) : 0);
  }
  function closeExpandedRow() {
    if (!expanded) return false;
    setExpanded(expanded, false);
    return true;
  }
  const menuElement = item => item.getElement?.() || item.menuElement?.() || null;
  const reducedMotion = () => target.matchMedia?.('(prefers-reduced-motion: reduce)').matches === true;
  function clearMenuAnimation(element, restore = true) {
    const entry = menuAnimations.get(element);
    if (!entry) return;
    menuAnimations.delete(element);
    entry.animation?.removeEventListener?.('finish', entry.finish);
    entry.animation?.cancel();
    if (!restore) return;
    element.inert = entry.wasInert;
    if (entry.wasInert) element.setAttribute('inert', '');
    else element.removeAttribute('inert');
    if (entry.ariaHidden === null || entry.ariaHidden === undefined) element.removeAttribute('aria-hidden');
    else element.setAttribute('aria-hidden', entry.ariaHidden);
    element.removeAttribute('data-droy-menu-phase');
  }
  function animateSessionMenu(element, opening = true, onClosed) {
    if (!element?.isConnected) { if (!opening) onClosed?.(); return; }
    const existing = menuAnimations.get(element);
    if (!opening && existing?.closing) {
      // Callers invalidate older close requests. Keep their latest commit while
      // retaining one exit animation, including its final React commit frame.
      if (existing.done) onClosed?.();
      else existing.onClosed = onClosed;
      return;
    }
    const opacity = Number(target.getComputedStyle?.(element).opacity ?? 1);
    clearMenuAnimation(element);
    if (reducedMotion() || !element.animate) { if (!opening) onClosed?.(); return; }
    const entry = { closing: !opening, wasInert: element.inert === true,
      ariaHidden: element.getAttribute?.('aria-hidden'), animation: null, finish: null, onClosed };
    if (!opening) {
      element.inert = true;
      element.setAttribute('inert', '');
      element.setAttribute('aria-hidden', 'true');
    }
    element.setAttribute('data-droy-menu-phase', opening ? 'opening' : 'closing');
    menuAnimations.set(element, entry);
    entry.finish = () => {
      if (menuAnimations.get(element) !== entry || entry.done) return;
      entry.done = true;
      if (opening) { menuAnimations.delete(element); element.removeAttribute('data-droy-menu-phase'); }
      else entry.onClosed?.();
    };
    entry.animation = element.animate([{ opacity: opening ? 0 : opacity }, { opacity: opening ? 1 : 0 }],
      { duration: 150, easing: opening ? 'ease-out' : 'ease-in', fill: opening ? 'none' : 'forwards' });
    entry.animation.addEventListener('finish', entry.finish, { once: true });
  }
  function openMenus() {
    return [...menus].filter(([, item]) => item.isOpen?.()).map(([id, item]) => ({ id, item, close: item.close }))
      .concat([...rows.values()].filter(entry => entry.options.isMenuOpen?.())
        .map(entry => ({ id: `session:${entry.options.sessionId}`, item: entry.options, close: entry.options.closeMenu })));
  }
  function clearTimer(gesture) {
    if (gesture?.timer !== undefined) target.clearTimeout(gesture.timer);
    if (gesture) gesture.timer = undefined;
  }
  function resetDrawer(gesture) {
    if (gesture?.kind !== 'drawer') return;
    gesture.drawer?.style?.removeProperty('transform');
    gesture.backdrop?.style?.removeProperty('opacity');
    gesture.frame?.removeAttribute('data-droy-dragging');
  }
  function cancelGestures({ closeRows = true } = {}) {
    const gesture = active;
    active = null;
    clearTimer(gesture);
    if (gesture?.entry) {
      if (gesture.lock === 'horizontal' || gesture.consumed) consumedClick = true;
      paintRow(gesture.entry, expanded === gesture.entry ? -gesture.width : 0);
    }
    resetDrawer(gesture);
    if (gesture?.capture?.hasPointerCapture?.(gesture.id)) gesture.capture.releasePointerCapture(gesture.id);
    if (closeRows) closeExpandedRow();
  }
  function capture(gesture, element) {
    if (!element?.setPointerCapture) return;
    gesture.capture = element;
    element.setPointerCapture(gesture.id);
  }
  function pointerDown(event) {
    if (event.pointerType !== 'touch') { inputMode(event.pointerType || 'mouse'); return; }
    inputMode('touch');
    pointers.add(event.pointerId);
    if (systemEdge(event.clientX)) {
      systemPointers.add(event.pointerId);
      consumedClick = false;
      // Android owns this sequence. Its eventual Back callback closes one
      // layer; pointer cancellation must not close an extra layer first.
      cancelGestures({ closeRows: false });
      return;
    }
    if (pointers.size > 1 || event.isPrimary === false) { cancelGestures(); return; }
    consumedClick = false;
    if (hasModal() || !drawerOpen()) return;
    const entry = rowFor(event.target);
    // Every session row owns its touch sequence, including unbound blank rows.
    if (!entry && event.target?.closest?.('[data-droy-session-row]')) return;
    if (entry) {
      if (entry.options.blank || entry.pending || interactive(event.target)) return;
      if (expanded && expanded !== entry) closeExpandedRow();
      const gesture = active = { kind: 'row', id: event.pointerId, x: event.clientX, y: event.clientY,
        lastX: event.clientX, lastAt: now(), startedAt: now(), entry, lock: null,
        width: actionWidth(entry), consumed: false };
      gesture.initial = expanded === entry ? -gesture.width : 0;
      gesture.timer = target.setTimeout(() => {
        if (disposed || active !== gesture || gesture.lock || pointers.size !== 1 || !entry.element.isConnected || hasModal()) return;
        gesture.consumed = true;
        consumedClick = true;
        closeExpandedRow();
        capture(gesture, entry.element);
        entry.options.openMenu?.();
      }, Math.max(200, Number(config().longPressMs) || 500));
      return;
    }
    const backdrop = event.target?.closest?.('[data-droy-backdrop]');
    const header = event.target?.closest?.('[data-droy-drawer-header]');
    // Android can enlarge a nearby header button's hit area. Keep taps on its
    // original action, but let an intentional horizontal header drag close us.
    if (header && (editable(event.target) || event.target?.closest?.('select'))) return;
    if (!backdrop && !header && interactive(event.target)) return;
    const drawer = event.target?.closest?.('[data-droy-sidebar]');
    if (!drawer && !backdrop) return;
    closeExpandedRow();
    const frame = doc.querySelector('[data-droy-frame]');
    const surface = drawer || doc.querySelector('[data-droy-sidebar]');
    active = { kind: 'drawer', id: event.pointerId, x: event.clientX, y: event.clientY,
      lastX: event.clientX, lastAt: now(), startedAt: now(), lock: null, offset: 0,
      drawer: surface, backdrop: backdrop || doc.querySelector('[data-droy-backdrop]'), frame,
      width: surface?.getBoundingClientRect?.().width || 320 };
  }
  function pointerMove(event) {
    const gesture = active;
    if (!gesture || event.pointerId !== gesture.id) return;
    const dx = event.clientX - gesture.x, dy = event.clientY - gesture.y;
    if (gesture.consumed) { block(event); return; }
    if (!gesture.lock && Math.hypot(dx, dy) >= slop()) {
      clearTimer(gesture);
      if (Math.abs(dy) >= Math.abs(dx)) { cancelGestures(); return; }
      if (Math.abs(dx) < Math.abs(dy) * 1.25) return;
      if (gesture.entry?.options.archived) { cancelGestures(); return; }
      gesture.lock = 'horizontal';
      if (gesture.kind === 'drawer' && dx >= 0) { cancelGestures(); return; }
      capture(gesture, gesture.entry?.element || gesture.drawer);
    }
    if (gesture.lock !== 'horizontal') return;
    block(event);
    gesture.velocity = (event.clientX - gesture.lastX) / Math.max(1, now() - gesture.lastAt);
    gesture.lastX = event.clientX;
    gesture.lastAt = now();
    if (gesture.kind === 'row') {
      const width = gesture.width;
      gesture.offset = Math.max(-width, Math.min(0, gesture.initial + dx));
      paintRow(gesture.entry, gesture.offset, true);
    } else {
      gesture.offset = Math.max(-gesture.width, Math.min(0, dx));
      gesture.frame?.setAttribute('data-droy-dragging', 'drawer');
      gesture.drawer?.style?.setProperty('transform', `translateX(${gesture.offset}px)`);
      gesture.backdrop?.style?.setProperty('opacity', String(1 + gesture.offset / gesture.width));
    }
  }
  function pointerUp(event) {
    pointers.delete(event.pointerId);
    if (systemPointers.delete(event.pointerId)) return;
    const gesture = active;
    if (!gesture || event.pointerId !== gesture.id) return;
    active = null;
    clearTimer(gesture);
    if (gesture.consumed) { consumedClick = true; block(event); }
    else if (gesture.lock === 'horizontal') {
      consumedClick = true;
      block(event);
      const freshVelocity = now() - gesture.lastAt < 100 ? gesture.velocity || 0 : 0;
      const flingTravel = Math.abs(event.clientX - gesture.x) >= Math.max(slop() * 2, 24 * (Number(config().dpToCss) || 1));
      if (gesture.kind === 'row') {
        const width = gesture.width;
        const open = flingTravel && freshVelocity < -0.45 ? true
          : flingTravel && freshVelocity > 0.45 ? false : gesture.offset < -width * 0.45;
        setExpanded(gesture.entry, open);
      } else {
        const close = gesture.offset < -gesture.width * 0.3 || flingTravel && freshVelocity < -0.5;
        resetDrawer(gesture);
        if (close && drawerOpen()) options.closeDrawer?.();
      }
    } else if (gesture.entry && expanded === gesture.entry) {
      closeExpandedRow();
      consumedClick = true;
      block(event);
    }
    if (gesture.capture?.hasPointerCapture?.(gesture.id)) gesture.capture.releasePointerCapture(gesture.id);
  }
  function pointerCancel(event) {
    pointers.delete(event.pointerId);
    if (systemPointers.delete(event.pointerId)) return;
    if (active?.id === event.pointerId) cancelGestures();
  }
  function feedbackMessage(message) {
    if (!doc.createElement || !doc.body?.appendChild) return;
    if (!feedback) {
      feedback = doc.createElement('div');
      feedback.className = 'droy-touch-feedback';
      feedback.setAttribute('role', 'alert');
      doc.body.appendChild(feedback);
    }
    target.clearTimeout(feedbackTimer);
    feedback.textContent = message;
    feedback.hidden = false;
    feedbackTimer = target.setTimeout(() => { feedback.hidden = true; }, 6000);
  }
  function sessionAction(event) {
    const detail = event.detail;
    if (!detail || !['archive', 'unarchive'].includes(detail.action)) return;
    for (const entry of rows.values()) if (entry.options.sessionId === detail.sessionId) {
      entry.pending = detail.phase === 'pending';
      if (entry.pending) { entry.element.setAttribute('data-droy-action-pending', 'true'); entry.element.setAttribute('aria-busy', 'true'); }
      else { entry.element.removeAttribute('data-droy-action-pending'); entry.element.removeAttribute('aria-busy'); }
      if (['succeeded', 'needsConfirmation', 'cancelled'].includes(detail.phase)) setExpanded(entry, false);
    }
    if (detail.phase === 'failed') feedbackMessage(detail.message || (detail.action === 'archive' ? '归档未完成，请重试' : '恢复未完成，请重试'));
    else if (detail.phase === 'pending' && feedback) feedback.hidden = true;
  }
  const controller = {
    bindSessionRow(element, rowOptions) {
      if (disposed) return () => {};
      const previous = rows.get(element);
      if (previous) setExpanded(previous, false);
      const entry = { element, options: rowOptions, pending: false };
      rows.set(element, entry);
      setExpanded(entry, false);
      return () => {
        if (rows.get(element) !== entry) return;
        if (active?.entry === entry) cancelGestures();
        setExpanded(entry, false);
        rows.delete(element);
      };
    },
    resetSessionRow(element) {
      const entry = rows.get(element);
      if (entry) setExpanded(entry, false);
    },
    bindMenu(id, menuOptions) {
      menus.set(id, menuOptions);
      return () => { if (menus.get(id) === menuOptions) { menus.delete(id); menuOrder.delete(id); } };
    },
    menuOpened(id) {
      menuOrder.set(id, ++menuSequence);
      closeExpandedRow();
      for (const menu of openMenus()) if (menu.id !== id) menu.close?.();
    },
    closeMenu(topElement) {
      const candidates = openMenus();
      const selected = topElement ? candidates.find(menu => {
        const element = menuElement(menu.item);
        return element === topElement || element?.contains?.(topElement);
      }) : candidates.sort((a, b) => (menuOrder.get(b.id) || 0) - (menuOrder.get(a.id) || 0)).at(0);
      if (!selected) return false;
      selected.close?.();
      return true;
    },
    animateSessionMenu,
    closeSessionMenuAnimated: (element, onClosed) => animateSessionMenu(element, false, onClosed),
    closeExpandedRow,
    cancelGestures,
    isTouchInteraction: () => lastInput === 'touch',
    restoreFocus(previous, fallback, closingOwner) {
      const active = doc.activeElement;
      const modal = active?.closest?.('[role="dialog"][aria-modal="true"], [role="menu"]');
      // Closing the drawer can finish after a newly opened modal has focused.
      // That modal owns focus until it closes; its original launcher must wait.
      if (modal?.isConnected && modal !== closingOwner && !modal.hasAttribute('data-droy-sidebar') && !active.closest('[inert]')) return;
      const visible = node => node?.isConnected && !node.closest?.('[inert], [aria-hidden="true"]') && node.getClientRects?.().length;
      if (lastInput === 'touch' && editable(previous)) {
        if (doc.activeElement === previous) previous.blur?.();
        if (visible(fallback) && !editable(fallback)) fallback.focus?.({ preventScroll: true });
      } else if (visible(previous)) previous.focus?.({ preventScroll: true });
      else if (visible(fallback)) fallback.focus?.({ preventScroll: true });
    },
    dispose() {
      disposed = true;
      cancelGestures();
      pointers.clear();
      systemPointers.clear();
      for (const entry of rows.values()) setExpanded(entry, false);
      rows.clear();
      menus.clear();
      menuOrder.clear();
      for (const element of menuAnimations.keys()) clearMenuAnimation(element);
      if (previousInputMode === null || previousInputMode === undefined) doc.documentElement.removeAttribute?.('data-droy-input');
      else doc.documentElement.setAttribute?.('data-droy-input', previousInputMode);
      for (const remove of listeners) remove();
      for (const node of touchSurfaces) node.removeEventListener('touchmove', horizontalTouchMove, touchMoveOptions);
      touchSurfaces.clear();
      observer?.disconnect();
      target.clearTimeout(feedbackTimer);
      feedback?.remove();
    }
  };
  listen(doc, 'pointerdown', pointerDown, true);
  listen(doc, 'pointermove', pointerMove, { capture: true, passive: false });
  listen(doc, 'pointerup', pointerUp, true);
  listen(doc, 'pointercancel', pointerCancel, true);
  listen(doc, 'lostpointercapture', event => {
    const gesture = active;
    // Android implicitly captures to the touched child. Moving capture to the
    // row/drawer releases that child without cancelling our explicit owner.
    if (gesture?.id !== event.pointerId || !gesture.capture || event.target !== gesture.capture
      || gesture.capture.hasPointerCapture?.(event.pointerId)) return;
    pointers.delete(event.pointerId);
    cancelGestures();
  }, true);
  listen(doc, 'click', event => {
    if (rowFor(event.target)?.pending) { block(event); return; }
    if (consumedClick && event.detail !== 0) { consumedClick = false; block(event); }
  }, true);
  listen(doc, 'contextmenu', event => { if (lastInput === 'touch' && rowFor(event.target) && !editable(event.target)) block(event); }, true);
  listen(doc, 'scroll', event => { if (event.target?.closest?.('[data-droy-sidebar]')) cancelGestures(); }, true);
  listen(doc, 'keydown', event => {
    if (event.isTrusted !== false || event.key !== 'Escape') inputMode('keyboard');
    if ((event.key === 'Enter' || event.key === ' ') && rowFor(event.target)?.pending) block(event);
  }, true);
  listen(doc, 'visibilitychange', () => { if (doc.visibilityState !== 'visible') { cancelGestures(); pointers.clear(); systemPointers.clear(); } });
  listen(target, 'pagehide', () => { cancelGestures(); pointers.clear(); systemPointers.clear(); });
  listen(target, 'droy-mobile-workspace-change', () => cancelGestures());
  listen(target, 'droy-mobile-session-action', sessionAction);
  const observer = target.MutationObserver ? new target.MutationObserver(records => {
    // Ordinary message text changes cannot add a drawer or remove its owned rows.
    const surfaces = '[data-droy-sidebar], [data-droy-backdrop]';
    const surfaceChanged = !records || records.some(record => record.type === 'attributes'
      || [...record.addedNodes, ...record.removedNodes].some(node => node.nodeType === 1
        && (node.matches?.(surfaces) || node.querySelector?.(surfaces))));
    const removedElement = !records || records.some(record => record.type === 'childList'
      && [...record.removedNodes].some(node => node.nodeType === 1));
    const detached = removedElement && [...rows.keys(), ...menuAnimations.keys()].some(element => !element.isConnected);
    if (!surfaceChanged && !detached) return;
    if (surfaceChanged) syncTouchSurfaces();
    for (const element of menuAnimations.keys()) if (!element.isConnected) clearMenuAnimation(element, false);
    if (!drawerOpen()) cancelGestures();
    for (const [element, entry] of rows) if (!element.isConnected) {
      if (active?.entry === entry) cancelGestures();
      if (expanded === entry) setExpanded(entry, false);
      rows.delete(element);
    }
  }) : null;
  observer?.observe(doc.documentElement, { childList: true, subtree: true, attributes: true, attributeFilter: ['data-droy-drawer-open'] });
  syncTouchSurfaces();
  return controller;
}

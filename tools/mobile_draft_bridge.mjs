// Retain the original DSH input binding while a blank session owns an unsent
// draft. Text stays in DSH storage; attachment bytes stay in its runtime registry.
export function createDroyDraftRetention(target, sessions, readDraft) {
  const entries = new Map();
  let archived = new Set();
  let disposed = false;
  const mobile = () => target.document.documentElement.hasAttribute('data-droy-mobile') || typeof target.DroyNative !== 'undefined';
  const changed = () => { if (!disposed) target.dispatchEvent(new target.CustomEvent('droy-mobile-drafts-change')); };
  const content = snapshot => (snapshot.draft || '').trim() !== '' || snapshot.attachmentIds.length > 0;
  function refresh(entry) {
    if (disposed || entries.get(entry.binding.sessionId) !== entry) return;
    const snapshot = entry.input.state.getSnapshot();
    const blank = entry.binding.session.getSnapshot().blank;
    const present = blank && !archived.has(entry.binding.sessionId) && content(snapshot);
    // A submission may optimistically clear the input before admission finishes.
    const submitting = blank && !archived.has(entry.binding.sessionId) && entry.reference
      && (snapshot.phase === 'adjudicating' || snapshot.phase === 'submitting');
    if (present && !entry.reference) entry.reference = sessions.retain(entry.binding.sessionId, {source:'workspaceOperation'});
    if (!present && !submitting && entry.reference) {
      const reference = entry.reference; entry.reference = null; reference.release();
    }
    if (present !== entry.present) { entry.present = present; changed(); }
  }
  function remove(entry) {
    if (entries.get(entry.binding.sessionId) !== entry) return;
    entries.delete(entry.binding.sessionId);
    for (const unsubscribe of entry.subscriptions) unsubscribe();
    const reference = entry.reference; entry.reference = null;
    reference?.release();
    if (entry.present) changed();
  }
  return {
    has(sessionId) {
      if (disposed || !mobile() || archived.has(sessionId)) return false;
      const entry = entries.get(sessionId);
      if (entry) return entry.present;
      return (readDraft(sessionId).text || '').trim() !== '';
    },
    track(binding, input) {
      if (disposed || !mobile() || entries.has(binding.sessionId)) return () => {};
      const entry = {binding,input,present:false,reference:null,subscriptions:[]};
      entries.set(binding.sessionId, entry);
      entry.subscriptions.push(input.state.subscribe(() => refresh(entry)), binding.session.subscribe(() => refresh(entry)));
      refresh(entry);
      return () => remove(entry);
    },
    setArchived(ids) {
      archived = new Set(ids);
      for (const entry of [...entries.values()]) refresh(entry);
      changed();
    },
    dispose() {
      if (disposed) return;
      disposed = true;
      for (const entry of [...entries.values()]) remove(entry);
    }
  };
}

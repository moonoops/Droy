function page(value) {
  if (!value || typeof value.snapshotId !== 'string' || typeof value.packageName !== 'string' || !Array.isArray(value.nodes)) return value;
  const nodes = value.nodes.map(node => Object.fromEntries(Object.entries(node).filter(([key, data]) =>
    !(['text', 'description', 'resourceId', 'hint'].includes(key) && data === ''))));
  const combined = [...new Set(value.nodes.flatMap(node => [node.text, node.description])
    .filter(text => typeof text === 'string' && text !== ''))].join('\n');
  const result = { ...value, nodes };
  // The native text aggregate usually repeats the ordered node labels exactly.
  // Extra text, including truncated or separately captured content, remains intact.
  if (typeof value.text === 'string' && value.text.trimEnd() === combined.trimEnd()) delete result.text;
  return result;
}

/** Format this tool's new result; never read or rewrite session history. */
export function renderPhoneResult(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return value;
  let result = page(value);
  for (const key of ['afterObservation', 'currentObservation', 'observation']) {
    if (value[key]) result = { ...result, [key]: page(value[key]) };
  }
  return result;
}

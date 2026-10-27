const hardware = new Set(['sensor', 'location', 'infrared', 'bluetooth', 'nfc', 'nfc_emulation',
  'wifi', 'cellular', 'network_probe', 'device_data', 'device_shortcut']);

/** New observations obey the already-active protected-session routing policy. */
export async function protectHardwareDisclosure(ctx, action, args, exec) {
  if (!hardware.has(action)) return;
  const privacy = ctx.get('droyPrivacy');
  if (!privacy || !(await privacy.status()).active) return;
  const route = await privacy.routeForAgent(exec.agent, 'conversation', exec.signal);
  const categories = ['derived'];
  if (['location', 'wifi', 'cellular', 'bluetooth', 'device_data'].includes(action)) categories.push('location');
  await privacy.beforeDisclosure({ sessionId: exec.agent.session.id, categories,
    sourceRefs: [`device-observation:${action}:${exec.callId}`], route, signal: exec.signal });
}

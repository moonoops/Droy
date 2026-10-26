import { createHash } from 'node:crypto';
import { decodePng } from './png.mjs';
import { personaArtDirection } from './pet-persona.mjs';

const actions = {
  idle: 'slow blink, open eyes, half closed, closed, open again',
  idleTail: 'gentle tail or appendage sway, left, center, right, center',
  idleStretch: 'resting, begins a comfortable stretch, full stretch, settles',
  tap: 'noticed a tap, soft delighted eye squint, small affectionate lean, settles',
  tapTilt: 'neutral, head tilts inquisitively, blinks, head returns',
  tapWave: 'neutral, raises a paw or equivalent limb, small greeting, lowers limb',
  pet: 'relaxes into a gentle touch, eyes soften, eyes close contentedly, relaxed open eyes',
  petShy: 'brief shy recoil, peeks back, leans affectionately toward the viewer, settles',
  working: 'focused attentive posture, looks ahead, listens, focused neutral',
  speaking: 'notices news, lifts head, small notifying gesture, attentive neutral',
  waiting: 'asks for attention, inquisitive head tilt, slight forward lean, patiently waits',
  success: 'pleased expression, small happy gesture, celebrates quietly, settles',
  blocked: 'notices obstacle, puzzled eye expression, slight head tilt, attentive wait',
  stopped: 'stops work, relaxes, small calm nod, neutral resting pose',
  enterWalk: 'four distinct sequential walking gait keyframes, actual alternating feet or species-appropriate locomotion',
  enterJump: 'crouches to prepare, launches, airborne with tucked limbs, lands with soft bent limbs',
  enterPeek: 'head and forepaws peeking, blinks, leans further out, emerges into full body',
  exitWalk: 'looks back toward viewer, turns to leave, walking step, next walking step',
  exitHide: 'full body, crouches behind an imaginary vertical edge, only head and paws remain, pulls back',
  peekLeft: 'head and forepaws peeking around a vertical edge toward the indicated facing direction, open eyes, half closed, closed, open',
  peekRight: 'head and forepaws peeking around a vertical edge toward the indicated facing direction, open eyes, half closed, closed, open',
};

export const PET_ACTION_GROUPS = [
  { id: 'idle-touch', label: '待机与轻触', states: ['idle', 'idleTail', 'idleStretch', 'tap'] },
  { id: 'affection', label: '招呼与抚摸', states: ['tapTilt', 'tapWave', 'pet', 'petShy'] },
  { id: 'task', label: '任务与回应', states: ['working', 'speaking', 'waiting', 'success'] },
  { id: 'movement', label: '行走与跳跃', states: ['blocked', 'stopped', 'enterWalk', 'enterJump'] },
  { id: 'arrival', label: '探身与退场', states: ['enterPeek', 'exitWalk', 'exitHide'] },
  { id: 'peek', label: '左右探头', states: ['peekLeft', 'peekRight'] },
];

export const PET_VARIANTS = {
  enter: ['enterPeek', 'enterWalk', 'enterJump'], exit: ['exitWalk', 'exitHide'],
  tap: ['tap', 'tapTilt', 'tapWave'], pet: ['pet', 'petShy'], idle: ['idle', 'idleTail', 'idleStretch'],
};

export function referencePrompt(pet) {
  return `Use case: stylized-concept. Asset type: transparent 2D mobile companion reference. `
    + `Create ONE complete character from this appearance description: ${JSON.stringify(pet.description)}. `
    + personaArtDirection(pet.persona)
    + ' Full body, three-quarter view facing LEFT with eyes directed left toward the screen interior. '
    + 'Distinct readable silhouette at 56dp, simple clear forms, complete limbs and features, no cropped extremities. '
    + 'Centered with 12% transparent margins, genuine transparent alpha background, no backdrop, floor, shadow, text, grid or watermark. '
    + 'Preserve asymmetrical markings and accessories consistently for later left and right views.';
}

export function actionPrompt(pet, group) {
  const rows = group.states.flatMap(name => [
    `${name}, character facing LEFT: ${actions[name]}`,
    `${name}, character facing RIGHT: ${actions[name].replaceAll('LEFT', '__RIGHT__').replaceAll('RIGHT', 'LEFT').replaceAll('__LEFT__', 'RIGHT')}`,
  ]);
  // Directions in the peek descriptions are subordinate to the explicit row facing.
  return `Use case: identity-preserve. Asset type: production 2D animation sprite atlas. `
    + 'The attached image is the approved CHARACTER IDENTITY REFERENCE. Keep its exact species, palette, graphic style, '
    + 'proportions, eyes, facial features, and accessories. Preserve asymmetric features on their anatomical sides; do not mirror text or markings. '
    + personaArtDirection(pet.persona)
    + ` Output a single transparent PNG, EXACTLY 4 equally wide columns and ${rows.length} equally high rows. `
    + 'Each cell contains one animation keyframe, read left to right. All cells have identical dimensions. '
    + 'Use the entire canvas evenly, no extra rows, no text labels, no drawn grid, no background or ground shadow. '
    + 'Each character has consistent scale and a stable ground baseline 10% above the cell bottom, with at least 8% transparent margin on EVERY cell edge. '
    + 'Four frames in each row must show the specified real pose changes, never four duplicates or a static sticker shifted sideways. '
    + 'The head, feet and tail must not cross a cell boundary. Entry/exit displacement is handled by the app; keep each frame centered. '
    + 'For side-peek poses draw head and forelimbs as a deliberate complete asset, not an accidentally sliced body. '
    + rows.map((row, index) => `Row ${index + 1}: ${row}.`).join(' ');
}

export function brandLogoPrompt(pet) {
  return 'Use case: identity-preserve. Asset type: mobile app and in-app brand logo. '
    + 'Use the attached approved CHARACTER IDENTITY REFERENCE. Keep its exact species, palette, graphic style, '
    + 'eyes, facial features, and defining accessories, with asymmetrical features on their anatomical sides. '
    + personaArtDirection(pet.persona)
    + 'Create ONE centered head or deliberate upper-body portrait with a clear, recognizable silhouette at 32dp. '
    + 'Do not include the full body or a scene. Keep every visible ear and accessory complete. '
    + 'Square canvas, genuine transparent alpha background and at least 12% transparent margin on every edge. '
    + 'No text, lettering, badge, frame, backdrop, floor, ground shadow, grid or watermark. '
    + 'The same logo will appear on the chat welcome screen, sidebar and app icon. '
    + 'Generate only this one transparent character foreground. The app supplies matching-shaped light and dark backing plates; '
    + 'do not generate separate theme variants, recolor the character, or paint a background into this image.';
}

export function validateBrandLogoPng(bytes) {
  const image = validatePetPng(bytes);
  if (image.width !== image.height) throw new Error('图片品牌标志需要为正方形透明 PNG，请重新生成');
  return image;
}

export function validatePetPng(bytes) {
  const header = Buffer.from(bytes);
  if (header.length >= 33 && header.subarray(0, 8).equals(Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]))
    && header.toString('ascii', 12, 16) === 'IHDR') {
    const width = header.readUInt32BE(16), height = header.readUInt32BE(20);
    if (width * height > 4_500_000 || width > 4096 || height > 4096)
      throw new Error('图片尺寸超限，请重新生成');
  }
  const image = decodePng(bytes);
  if (image.channels !== 4 || image.width < 256 || image.height < 256 || image.width * image.height > 4_500_000)
    throw new Error('图片需要为尺寸合适的透明 PNG，请重新生成');
  let clear = 0, painted = 0;
  for (let offset = 3; offset < image.pixels.length; offset += 4) {
    if (image.pixels[offset] < 8) clear++;
    if (image.pixels[offset] > 32) painted++;
  }
  if (clear < image.width * image.height * .08 || painted < 100)
    throw new Error('图片缺少透明背景或角色内容，请重新生成');
  // Match the native package acceptance contract before a candidate can become immutable/ready.
  if (image.pixels[3] !== 0 || image.pixels[image.pixels.length - 1] !== 0)
    throw new Error('图片左上角和右下角需要完全透明，请重新生成');
  return image;
}

/** Cell bounds come from the fixed grid, never from model-written coordinates. */
export function inspectActionAtlas(bytes, group) {
  const image = validatePetPng(bytes), rows = group.states.length * 2;
  const frames = [], states = {};
  for (let row = 0; row < rows; row++) {
    const startY = Math.floor(row * image.height / rows), endY = Math.floor((row + 1) * image.height / rows);
    const cellHeight = endY - startY;
    const hashes = new Set(), cells = [];
    let left = Infinity, top = Infinity, right = 0, bottom = 0;
    for (let col = 0; col < 4; col++) {
      const startX = Math.floor(col * image.width / 4), endX = Math.floor((col + 1) * image.width / 4);
      let count = 0, border = 0;
      const hash = createHash('sha256');
      for (let y = startY; y < endY; y++) {
        hash.update(image.pixels.subarray((y * image.width + startX) * 4, (y * image.width + endX) * 4));
        for (let x = startX; x < endX; x++) {
          if (image.pixels[(y * image.width + x) * 4 + 3] <= 32) continue;
          count++;
          const localX = x - startX, localY = y - startY;
          left = Math.min(left, localX); right = Math.max(right, localX + 1);
          top = Math.min(top, localY); bottom = Math.max(bottom, localY + 1);
          if (localX < 2 || localX >= endX - startX - 2 || localY < 2 || localY >= cellHeight - 2) border++;
        }
      }
      if (count < 30 || border > 3) throw new Error(`${group.label}的第${row + 1}行存在空帧或越界，请重做这一组`);
      hashes.add(hash.digest('hex'));
      cells.push({ startX, startY, width: endX - startX, height: cellHeight });
    }
    if (hashes.size < 2) throw new Error(`${group.label}的第${row + 1}行动作没有变化，请重做这一组`);
    const indexes = cells.map(cell => {
      const x = Math.max(0, left - 2), y = Math.max(0, top - 2);
      const width = Math.min(cell.width, right + 2) - x, height = Math.min(cell.height, bottom + 2) - y;
      const index = frames.length;
      frames.push({ sourceRect: [cell.startX + x, cell.startY + y, width, height], anchor: [.5, 1] });
      return index;
    });
    const baseName = group.states[Math.floor(row / 2)], rightFacing = row % 2 === 1;
    // peekLeft is the left-edge pose, whose default faces right into the screen.
    const key = baseName === 'peekLeft' ? (rightFacing ? baseName : `${baseName}Left`) : rightFacing ? `${baseName}Right` : baseName;
    const leftKey = baseName === 'peekLeft' ? `${baseName}Left` : baseName;
    const rightKey = baseName === 'peekLeft' ? baseName : `${baseName}Right`;
    states[key] = {
      label: `${baseName} · ${rightFacing ? '朝右' : '朝左'}`, atlas: group.id, frames: indexes,
      durationsMs: baseName.startsWith('peek') ? [900, 90, 110, 900] : [180, 180, 180, 240],
      displayHeightDp: baseName.startsWith('peek') ? 36 : 56, playback: 'once_then_hold',
      facing: rightFacing ? 'right' : 'left', mirrorSafe: false, leftState: leftKey, rightState: rightKey,
    };
  }
  return { atlas: { file: `${group.id}.png`, width: image.width, height: image.height, frames }, states };
}

export function createPetManifest(pet, groups) {
  const atlases = {}, states = {};
  for (const group of PET_ACTION_GROUPS) {
    const parsed = groups[group.id];
    if (!parsed) throw new Error('动作尚未全部生成');
    atlases[group.id] = parsed.atlas;
    Object.assign(states, parsed.states);
  }
  return {
    formatVersion: 2, id: pet.id, name: pet.name, description: pet.description, reference: 'reference.png',
    branding: { logo: 'brand-logo.png' },
    persona: pet.persona, variants: PET_VARIANTS, atlases, states,
    rendering: { bodyDisplayHeightDp: 56, peekDisplayHeightDp: 36, minimumTouchTargetDp: 48, anchor: 'bottom_center' },
  };
}

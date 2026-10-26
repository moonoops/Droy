// Pet copy is deliberately public-facing. Never derive it from reasoning or raw tool payloads.
export function petText(value, limit) {
  if (typeof value !== 'string') return null;
  const text = value.replace(/\s+/gu, ' ').trim();
  if (!text || [...text].length > limit) return null;
  if (/(?:https?:\/\/|\bBearer\s|\bsk-[\w-]{8,}|-----BEGIN|(?:api[_ -]?key|token|password|密码|验证码|口令)\s*[:=：]\s*\S+|\b\d{7,}\b|[\w.+-]+@[\w.-]+\.[a-z]{2,})/iu.test(text)) return null;
  return text;
}

export const petProgressTool = {
  name: 'droy_pet_progress',
  description: '更新一句面向用户的任务状态，供伙伴形象的任务面板和运行状态展示，不自动弹出提醒。任务进入较长等待前用waiting说明等待时间或条件及下一步；恢复执行时更新阶段。其他进展仅用于目标步骤变化或实际阻碍，不逐动作调用；不包含内部推理、账号、凭据、验证码、屏幕原文或工具参数。需要主动提醒时按需选用通知插件。它不建立定时任务、不实际等待、不创建审批，也不能代替ask_user_question。',
  parameters: {
    summary: { type: 'string', description: '一句简短的对外进展，最多120字，例如“正在筛选符合预算的商品”或“等待10:00开抢，到点后领取优惠券”。' },
    phase: { type: 'string', enum: ['thinking', 'executing', 'verifying', 'waiting', 'blocked'], description: '当前实际阶段；waiting 表示等待目标时间或条件，需另行执行实际等待；blocked 仅表示已遇到阻碍，不能据此生成审批。' },
  },
};

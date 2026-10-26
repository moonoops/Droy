export const voiceToolDefinition = {
  name: 'droy_voice',
  description: '查询已配置的助手音色、试听或更换默认音色。仅操作已有音色，不添加服务或收集凭据。',
  parameters: {
    operation: { type: 'string', required: true, enum: ['list_voices', 'set_voice', 'preview'], description: '查询、设为默认或试听。' },
    voiceId: { type: 'string', description: '从 list_voices 返回的音色 id；名称有歧义时先让用户选择。' },
  },
};

export function createVoiceTool(service) {
  return {
    ...voiceToolDefinition,
    output: { schema: { type: 'json' }, render: (_args, value) => [{ type: 'text', text: JSON.stringify(value) }] },
    async execute(args, exec) {
      if (!exec.agent?.id) throw new Error('语音设置操作缺少可信调用身份');
      exec.signal.throwIfAborted();
      if (args.operation === 'list_voices') return service.run({ operation: 'voices' });
      if (!args.voiceId) throw new Error('请选择一个已配置的音色');
      if (args.operation === 'set_voice') return service.run({ operation: 'set_voice', id: args.voiceId });
      if (args.operation === 'preview') return service.run({ operation: 'preview_voice', id: args.voiceId });
      throw new Error('语音设置操作无效');
    },
  };
}

const command = '\tasync selectModel(request) {';
const forwarder = '\t\tselectModel(request) {\n\t\t\treturn this.commands.selectModel(request);\n\t\t}';
const saveDefault = `\t\t\t\tthis.ctx.agentDefaultModel.saveSelection(selected).catch((error) => {
\t\t\t\t\tthis.ctx.logger.warn(\`session-controller: model selection changed for the Session but the default was not saved: \${String(error)}\`);
\t\t\t\t});`;

/** Keep native Session overrides local while preserving the upstream Web command default. */
export function patchSessionModelSelection(source) {
  for (const anchor of [command, forwarder, saveDefault]) {
    if (source.split(anchor).length !== 2)
      throw new Error('DSH Session model selection contract changed');
  }
  return source.replace(saveDefault,
    saveDefault.replace('this.ctx.agentDefaultModel.saveSelection(selected)',
      'if (request.persistDefault !== false) this.ctx.agentDefaultModel.saveSelection(selected)'));
}

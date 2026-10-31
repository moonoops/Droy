// DSH 0.2.1-rc.5 exposes row faces but fixes section order internally.
// Keep this seam separate from the plugin's menu policy and preserve search ranking.
export function patchMediaCommandMenu(source) {
  const anchor = 'return req.query === "" ? sectionRows(visible, this.t) : (0, _deepseek_ai_dsh_client_ui_primitives.rankByName)(visible, req.query);';
  if (source.split(anchor).length !== 2) throw new Error('Media command menu upstream anchor changed');
  return source.replace(anchor, `if (req.query !== "") return (0, _deepseek_ai_dsh_client_ui_primitives.rankByName)(visible, req.query);
                const menuRows = sectionRows(visible, this.t);
                return this.ctx.bail("droy/command-menu-rows", menuRows) ?? menuRows;`);
}

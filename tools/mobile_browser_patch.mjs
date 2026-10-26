import { createNativeBrowserPage } from '../runtime/mobile/browser-page.mjs';

export function patchSidebarBrowser(source) {
  function replaceOnce(from, to) {
    if (source.split(from).length !== 2) throw new Error('Mobile browser patch anchor changed');
    source = source.replace(from, to);
  }
  if (source.includes('createNativeBrowserPage')) throw new Error('Mobile browser patch anchor changed');
  replaceOnce('\t\t\t\t\tinitialUrl: initialUrl.current,', '\t\t\t\t\tinitialUrl: initialUrl.current,\n                    nativeSessionId: tab.navigation.params?.nativeSessionId,');
  replaceOnce('\t\t\t\tthis.page = options.createPage({\n\t\t\t\t\tinitial: options.initial,',
    '\t\t\t\tthis.page = options.createPage({\n                    tabId: options.tabId,\n                    nativeSessionId: options.nativeSessionId,\n\t\t\t\t\tinitial: options.initial,');
  replaceOnce('window.open(externalUrl, "_blank", "noopener,noreferrer");', `if (typeof window.DroyNative !== "undefined") {
                                        window.DroyMobile?.openExternalBrowser(externalUrl).catch(error => window.DroyMobile?.reportBrowserError(error));
                                    } else window.open(externalUrl, "_blank", "noopener,noreferrer");`);
  const anchor = '\t\tfunction createIframePage(options) {';
  replaceOnce(anchor, `${createNativeBrowserPage.toString()}\n${anchor}
            if (typeof window.DroyNative !== "undefined") return createNativeBrowserPage(options, {
                createStore: _deepseek_ai_dsh_client_store.createSnapshotStore,
                document,
                window,
                browserPage: args => {
                    if (!window.DroyMobile?.browserPage) return Promise.reject(new Error((document.documentElement.lang || "zh").startsWith("zh") ? "手机浏览器尚未就绪，请重试" : "Phone browser is not ready. Try again."));
                    return window.DroyMobile.browserPage(args);
                }
            });`);
  return source;
}

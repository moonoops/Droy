export function patchNodePtyLoader(source) {
  const anchor = 'function loadNativeModule(name) {';
  if (source.split(anchor).length !== 2 || source.includes('DROY_NATIVE_DIR')) {
    throw new Error('Mobile node-pty loader anchor changed');
  }
  return source.replace(anchor, `${anchor}
    if (process.platform === 'android' && name === 'pty') {
        if (!process.env.DROY_NATIVE_DIR) throw new Error('DROY_NATIVE_DIR is required for Android PTY');
        var nativePath = require('path').join(process.env.DROY_NATIVE_DIR, 'libdroy_pty.so');
        var nativeModule = {exports: {}};
        process.dlopen(nativeModule, nativePath);
        return {dir: require('path').dirname(nativePath), module: nativeModule.exports};
    }`);
}

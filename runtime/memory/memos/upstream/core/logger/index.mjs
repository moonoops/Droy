// Droy adapter: these storage diagnostics must not create upstream log buffers,
// files, broadcasts or viewer endpoints containing private memory metadata.
const noop = () => {};
export const rootLogger = Object.freeze({ child: () => rootLogger, trace: noop, debug: noop, info: noop, warn: noop, error: noop });

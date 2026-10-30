// Generated from MemTensor/MemOS 64388b50e2a15528bcdf032e65a1ac792bcf63ee: core/time.ts
let _now = ()=>Date.now();
export function now() {
    return _now();
}
export function setNow(fn) {
    const previous = _now;
    _now = fn;
    return ()=>{
        _now = previous;
    };
}
export function hrNowMs() {
    return Number(process.hrtime.bigint()) / 1_000_000;
}
export function formatDurationMs(ms) {
    if (!Number.isFinite(ms)) return "?";
    const abs = Math.abs(ms);
    if (abs < 1) return ms.toFixed(2) + "ms";
    if (abs < 1000) return Math.round(ms) + "ms";
    if (abs < 60_000) return (ms / 1000).toFixed(ms < 10_000 ? 2 : 1) + "s";
    const minutes = Math.floor(ms / 60_000);
    const seconds = Math.round(ms % 60_000 / 1000);
    return seconds === 0 ? `${minutes}m` : `${minutes}m${seconds}s`;
}
export function isoFromEpochMs(ms, tz, opts = {}) {
    if (!tz || tz === "UTC") return new Date(ms).toISOString();
    const d = new Date(ms);
    const parts = dateTimeParts(d, tz);
    const base = `${parts.year}-${parts.month}-${parts.day}T${parts.hour}:${parts.minute}:${parts.second}.${parts.fractionalSecond ?? "000"}`;
    return opts.offset ? base + offsetForTimeZone(d, tz) : base;
}
function dateTimeParts(d, tz) {
    const fmt = new Intl.DateTimeFormat("en-CA", {
        timeZone: tz,
        year: "numeric",
        month: "2-digit",
        day: "2-digit",
        hour: "2-digit",
        minute: "2-digit",
        second: "2-digit",
        fractionalSecondDigits: 3,
        hour12: false
    });
    const out = {};
    for (const part of fmt.formatToParts(d)){
        if (part.type !== "literal") out[part.type] = part.value;
    }
    return out;
}
function offsetForTimeZone(d, tz) {
    const fmt = new Intl.DateTimeFormat("en-US", {
        timeZone: tz,
        timeZoneName: "longOffset",
        hour: "2-digit"
    });
    const zone = fmt.formatToParts(d).find((part)=>part.type === "timeZoneName")?.value;
    if (!zone || zone === "GMT" || zone === "UTC") return "+00:00";
    return zone.replace(/^GMT/, "");
}

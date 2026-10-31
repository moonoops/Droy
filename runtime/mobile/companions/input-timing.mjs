/** Bound the input batch, not the time a generated answer sits on screen. */
export function inputBatchDelay({now, firstAt=now, count=1, lastReplyAt=0, imageOnly=false, overrideMs}={}) {
  if(Number.isFinite(overrideMs))return Math.max(0,overrideMs);
  const active=lastReplyAt>0&&now-lastReplyAt<30000;
  const settle=count>1?2000:active?5000:2000;
  const initial=imageOnly&&count===1?Math.max(4000,settle):settle;
  return Math.max(0,Math.min(initial,8000-(now-firstAt)));
}

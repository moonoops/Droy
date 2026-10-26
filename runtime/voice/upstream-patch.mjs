// Keep the DSH voice plugin active; Droy replaces only its UI contribution.
export function patchVoiceInput(source) {
  const anchor = '\t\texports.apply = apply;';
  if (source.split(anchor).length !== 2) throw new Error('DSH voice-input export anchor changed');
  const activation = '\t\t\treturn await mountVoiceInput(ctx, TYPERT_REMOTE);';
  if (source.split(activation).length !== 2) throw new Error('DSH voice-input activation anchor changed');
  return source.replace(activation, '\t\t\treturn await ctx.remote.$mount(TYPERT_REMOTE);').replace(anchor, `
        exports.createDroyVoiceInput = async function(ctx) {
          let face;
          await ctx.inject(["remote.speech"], scope => {
            const microphone = createMicrophoneDeviceStore();
            const readiness = observeReadiness(scope);
            const recordings = new Set();
            scope.effect(() => async () => {
              await Promise.all([...recordings].map(recording => recording.dispose()));
              await readiness.dispose();
            });
            face = {
              readiness: readiness.state,
              createRecording() {
                const recording = new Recording(() => recordings.delete(recording), microphone.getSnapshot().id);
                recordings.add(recording);
                return recording;
              },
              transcribe: (request, signal) => scope.remote.speech.transcribe(request, signal),
              configure: patch => scope.remote.speech.configure(patch),
            };
          });
          return face;
        };
${anchor}`);
}

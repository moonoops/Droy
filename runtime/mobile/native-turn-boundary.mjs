import { turnBoundaryProjectionDefinition } from '@deepseek-ai/dsh-agent-loop';

// The SDK boundary is host-only (no wire view). Publish the same replayable fold
// under a product key so cold history, follow and control share one sequence space.
export const nativeTurnBoundaryProjection = {
  ...turnBoundaryProjectionDefinition,
  key: 'droyTurnBoundary',
  wire: {
    viewSchema: turnBoundaryProjectionDefinition.stateSchema,
    view: state => state,
  },
};

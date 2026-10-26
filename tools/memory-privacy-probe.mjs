// P1-01 laboratory probe. Uses real DSH services and local HTTP only; this is
// deliberately not installed as a product privacy policy.
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { mkdtemp, mkdir, writeFile, readFile, rm, symlink } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { tmpdir } from 'node:os';
import { createServer } from 'node:http';

const installed = createRequire(new URL('../runtime/dsh/package.json', import.meta.url));
const load = name => import(pathToFileURL(installed.resolve(name)).href);
const MARKER = 'FICTIONAL_DROY_PRIVATE_MEMORY_P1_01';

export async function runPrivacyProbe() {
  const [{ Context }, { default: LlmRuntime, LlmAdapter }, { default: SystemPrompt },
    { default: ToolRuntime }, { default: Projections }, { default: SandboxPolicy },
    { default: SandboxedFileSystem }, { default: WorkingDirectory }, ToolFs] = await Promise.all([
    load('@deepseek-ai/cordis'), load('@deepseek-ai/dsh-llm'), load('@deepseek-ai/dsh-system-prompt'),
    load('@deepseek-ai/dsh-tools'), load('@deepseek-ai/dsh-session-projection'), load('@deepseek-ai/dsh-sandbox-policy'),
    load('@deepseek-ai/dsh-fs-sandbox'), load('@deepseek-ai/dsh-working-directory'), load('@deepseek-ai/dsh-tool-fs'),
  ]);
  const root = await mkdtemp(join(tmpdir(), 'droy-privacy-probe-'));
  const workspace = join(root, 'workspace');
  const privateDirectory = join(root, 'synthetic-private');
  const privatePath = join(privateDirectory, 'fictional-record.txt');
  const received = [];
  const ctx = new Context();
  const receiver = createServer(async (req, res) => {
    try {
      let body = '';
      for await (const chunk of req) {
        body += chunk;
        if (body.length > 65536) throw new Error('PROBE_BODY_TOO_LARGE');
      }
      const value = JSON.parse(body);
      received.push({ endpoint: req.url, sessionId: value.sessionId ?? null,
        purpose: value.purpose ?? 'conversation', containsMarker: body.includes(MARKER) });
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end('{"ok":true}');
    } catch {
      res.writeHead(400); res.end();
    }
  });
  let listening = false;
  try {
    await mkdir(workspace); await mkdir(privateDirectory);
    await writeFile(privatePath, MARKER + '\n', { mode: 0o600 });
    await symlink(privateDirectory, join(workspace, 'private-link'));
    await new Promise((done, reject) => {
      receiver.once('error', reject);
      receiver.listen(0, '127.0.0.1', () => { receiver.off('error', reject); done(); });
    });
    listening = true;
    const baseUrl = `http://127.0.0.1:${receiver.address().port}`;
    const routes = new Map([['probe-approved', `${baseUrl}/approved`], ['probe-unapproved', `${baseUrl}/unapproved`]]);
    const approvedDestinations = new Set([routes.get('probe-approved')]);
    const protectedSessions = new Set(['synthetic-protected-session']);

    class HttpFixtureAdapter extends LlmAdapter {
      async resolveModel(provider, id) {
        return { provider, id, name: 'Local synthetic privacy receiver', inputModalities: ['text'] };
      }
      async *stream(options) {
        const response = await fetch(routes.get(options.provider), {
          method: 'POST', headers: { 'content-type': 'application/json' },
          body: JSON.stringify({ sessionId: options.sessionId, purpose: options.purpose, messages: options.messages }),
          signal: AbortSignal.timeout(3000),
        });
        assert.equal(response.status, 200);
        await response.json();
        yield { type: 'finish', reason: { kind: 'stop' } };
      }
    }

    await ctx.plugin(LlmRuntime);
    ctx.llm.registerAdapter([...routes.keys()], new HttpFixtureAdapter());
    // This intentionally incomplete policy demonstrates why identity propagation
    // and source access enforcement are required in addition to this hook.
    ctx.on('llm/stream', (options, next) => {
      if (protectedSessions.has(options.sessionId) && !approvedDestinations.has(routes.get(options.provider))) {
        throw new Error('PROBE_ENDPOINT_NOT_AUTHORIZED');
      }
      return next();
    });
    async function send(provider, sessionId, purpose, text = MARKER) {
      const options = { provider, model: 'synthetic', messages: [{ role: 'user', content: [{ type: 'text', text }] }],
        ...(sessionId === undefined ? {} : { sessionId }), ...(purpose === undefined ? {} : { purpose }) };
      const chunks = [];
      for await (const chunk of ctx.llm.stream(options)) chunks.push(chunk);
      assert.equal(chunks.at(-1)?.reason?.kind, 'stop', JSON.stringify(chunks));
    }
    const checks = [];
    for (const purpose of [undefined, 'compaction', 'session-title']) {
      const before = received.length;
      await assert.rejects(send('probe-unapproved', 'synthetic-protected-session', purpose), /PROBE_ENDPOINT_NOT_AUTHORIZED/);
      assert.equal(received.length, before);
      await send('probe-approved', 'synthetic-protected-session', purpose);
      assert.equal(received.length, before + 1);
      assert.equal(received.at(-1).endpoint, '/approved');
      assert.equal(received.at(-1).containsMarker, true);
      checks.push({ id: `session-bound-${purpose ?? 'conversation'}`, status: 'pass', deniedHttpRequests: 0, approvedHttpRequests: 1 });
    }

    const beforeRouteChange = received.length;
    routes.set('probe-approved', `${baseUrl}/unapproved`);
    await assert.rejects(send('probe-approved', 'synthetic-protected-session'), /PROBE_ENDPOINT_NOT_AUTHORIZED/);
    assert.equal(received.length, beforeRouteChange);
    routes.set('probe-approved', `${baseUrl}/approved`);
    checks.push({ id: 'same-provider-changed-endpoint', status: 'pass', deniedHttpRequests: 0 });

    await send('probe-unapproved', undefined);
    assert.equal(received.at(-1).containsMarker, true);
    checks.push({ id: 'anonymous-private-request', status: 'gap-demonstrated', unapprovedHttpRequests: 1,
      reason: 'A session-only guard cannot attribute private content when sessionId is absent.' });

    await ctx.plugin(SystemPrompt);
    await ctx.plugin(ToolRuntime, { mode: 'native' });
    await ctx.plugin(Projections);
    // Omit mode to exercise the public service's actual read-only default.
    await ctx.plugin(SandboxPolicy, { workspaceRoot: workspace });
    await ctx.plugin(SandboxedFileSystem, { cwd: workspace });
    await ctx.plugin(WorkingDirectory, { defaultDirectory: workspace });
    await ctx.plugin(ToolFs, { readImage: false });
    assert.equal(ctx.sandboxPolicy.defaultMode, 'read-only');
    let calls = 0;
    const tool = (name, args) => ctx.tools.execute({ name, arguments: args,
      callId: `probe-${++calls}`, signal: new AbortController().signal });
    const absoluteRead = await tool('read', { file_path: privatePath });
    assert.equal(absoluteRead.isError, false, JSON.stringify(absoluteRead));
    const content = absoluteRead.content.filter(item => item.type === 'text').map(item => item.text).join('\n');
    assert.ok(content.includes(MARKER));
    const symlinkRead = await tool('read', { file_path: 'private-link/fictional-record.txt' });
    assert.equal(symlinkRead.isError, false, JSON.stringify(symlinkRead));
    assert.ok(JSON.stringify(symlinkRead.content).includes(MARKER));
    const deniedWrite = await tool('write', { file_path: join(workspace, 'denied.txt'), content: 'synthetic write' });
    assert.equal(deniedWrite.isError, true);
    assert.ok(JSON.stringify(deniedWrite).includes('FS_SANDBOX_DENIED'));
    checks.push({ id: 'official-read-only-filesystem', status: 'gap-demonstrated', sandboxMode: ctx.sandboxPolicy.defaultMode,
      outsideWorkspaceReadable: true, symlinkOutsideReadable: true, writeDenied: true,
      reason: 'The official filesystem sandbox restricts modifications, not reads.' });

    // A legitimate tool result is copied to a new session: no marker scanning
    // or invented data label is used by the incomplete hook.
    await send('probe-unapproved', 'synthetic-unmarked-session', undefined, content);
    assert.equal(received.at(-1).containsMarker, true);
    assert.equal(received.at(-1).endpoint, '/unapproved');
    checks.push({ id: 'unmarked-session-file-result', status: 'gap-demonstrated', unapprovedHttpRequests: 1,
      reason: 'Private filesystem reads must be denied or propagate protection before model submission.' });

    const llmManifest = JSON.parse(await readFile(installed.resolve('@deepseek-ai/dsh-llm/package.json'), 'utf8'));
    return { schemaVersion: 1, probe: 'P1-01 privacy boundary', nodeVersion: process.version, platform: process.platform,
      dshVersion: llmManifest.version, productionPrivacyReady: false, checks,
      localHttpReceiptCount: received.length, allDestinationsLoopback: true, realPrivateDataUsed: false,
      evidenceBoundary: 'Real installed DSH LLM middleware, tool registry, filesystem and sandbox policy. A scripted HTTP fixture adapter substitutes for a model provider; no production guard, persistent session protection, full mobile profile or Android privacy enforcement is claimed.',
      sourceRefs: [
        '@deepseek-ai/dsh-llm: LlmRuntime.stream / llm/stream; GenerateOptions.sessionId is optional',
        '@deepseek-ai/dsh-fs-sandbox: SandboxedFileSystem write/edit fence, inherited read methods',
        '@deepseek-ai/dsh-tool-fs: read tool and session-cwd',
        '@deepseek-ai/dsh-sandbox-policy: default mode read-only',
      ],
    };
  } finally {
    await ctx.fiber.dispose();
    if (listening) {
      receiver.closeAllConnections();
      await new Promise((done, reject) => receiver.close(error => error ? reject(error) : done()));
    }
    await rm(root, { recursive: true, force: true });
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const result = await runPrivacyProbe();
  process.stdout.write(JSON.stringify(result, null, 2) + '\n');
}

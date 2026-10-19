import { strict as assert } from 'node:assert';
import { resolve } from 'node:path';

const runtime = 'docker';
const image = process.argv[2] || 'unitycatalog-ui:local';
const backend = Bun.serve({
  port: 0,
  hostname: '127.0.0.1',
  fetch: () => Response.json({ proxy: 'uc-smoke' }),
});
const name = `uc-ui-smoke-${process.pid}`;

function run(args: string[], stdin?: string) {
  const result = Bun.spawnSync(args, {
    stdin: stdin === undefined ? undefined : Buffer.from(stdin),
  });
  if (result.exitCode !== 0) throw new Error(result.stderr.toString());
  return result.stdout.toString();
}

try {
  for (const proxyApiRequests of [true, false]) {
    const reservation = Bun.serve({
      port: 0,
      hostname: '127.0.0.1',
      fetch: () => new Response(),
    });
    const port = reservation.port;
    reservation.stop(true);
    const values = {
      auth: {
        enabled: true,
        provider: 'oidc',
        clientSecretName: 'smoke-client',
        oidcIssuer: 'https://issuer.example.com',
        oidcRedirectUri: `http://localhost:${port}/login/callback`,
      },
      server: { jwtKeypairSecret: { create: false } },
      ui: { proxyApiRequests, serverUrl: `http://127.0.0.1:${backend.port}` },
    };
    const rendered = run(
      [
        'helm',
        'template',
        'smoke',
        resolve(import.meta.dir, '../../helm'),
        '-f',
        '-',
      ],
      JSON.stringify(values),
    );
    const docs = rendered
      .split(/^---\s*$/m)
      .filter((part) => part.trim())
      .map((part) => Bun.YAML.parse(part) as any);
    const container = docs.find(
      (doc) =>
        doc.kind === 'Deployment' &&
        doc.spec.template.spec.containers[0].name === 'ui',
    ).spec.template.spec.containers[0];
    const env = container.env.flatMap((entry: any) => [
      '-e',
      `${entry.name}=${entry.value ?? 'smoke-client-id'}`,
    ]);
    run([
      runtime,
      'run',
      '-d',
      '--name',
      name,
      '--network',
      'host',
      '-e',
      `PORT=${port}`,
      '-e',
      'BROWSER=none',
      '-e',
      'CI=true',
      ...env,
      image,
      ...container.command,
    ]);
    try {
      const origin = `http://localhost:${port}`;
      let html = '';
      for (let attempt = 0; attempt < 120; attempt++) {
        try {
          const response = await fetch(`${origin}/login/callback`, {
            headers: { Accept: 'text/html' },
          });
          if (response.ok) {
            html = await response.text();
            break;
          }
        } catch {
          /* CRA is still starting. */
        }
        await Bun.sleep(1000);
      }
      assert(
        html.includes('<div id="root"></div>'),
        'callback deep link did not serve the UI',
      );
      const bundle = await fetch(`${origin}/static/js/bundle.js`);
      assert(bundle.ok, 'UI bundle was not served');
      assert(
        (await bundle.text()).includes('Completing sign-in'),
        'OIDC callback was not bundled',
      );
      assert(
        !run([runtime, 'logs', name]).includes('Failed to compile'),
        'CRA compilation failed',
      );
      if (proxyApiRequests) {
        const response = await fetch(`${origin}/api/smoke`, {
          headers: { Accept: 'application/json' },
        });
        assert.deepEqual(await response.json(), { proxy: 'uc-smoke' });
      }
      console.log(
        `UI image: chart bun startup, callback routing, proxy=${proxyApiRequests} passed`,
      );
    } catch (error) {
      console.error(run([runtime, 'logs', name]));
      throw error;
    } finally {
      run([runtime, 'rm', '-f', name]);
    }
  }
} finally {
  backend.stop(true);
}

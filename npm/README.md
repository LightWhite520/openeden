# npm CLI launcher

Requires Node.js 22+ and Java 21+. Start the OpenEden server separately using
the existing server instructions. This package only builds and starts the JVM CLI.
It inherits terminal input/output without a Gradle process between the CLI and terminal.

From the repository root:

```powershell
npm run build
npm start
```

`npm run cli` is an alias for `npm start`. Both enter the interactive client.
The default server URL is `http://127.0.0.1:8080`. The JVM CLI reads the address
from `%USERPROFILE%\.openeden\config.json`, created on first launch. To use
another server, edit its `serverUrl` and restart the client:

```json
{
  "serverUrl": "http://127.0.0.1:8080",
  "userId": "your-existing-user-id"
}
```

Keep the existing `userId`. The launcher sets `OPENEDEN_SERVER_URL`, but the
current JVM client does not read it; setting that variable alone does not
override the saved address.

Forward client arguments with `npm start -- state` or
`npm start -- chat --message "Hello"`. For direct terminal launch, use
`node npm/bin/openeden.mjs`.

Create a distributable package with `npm pack` (automatically builds the CLI).
On the destination machine:

```powershell
npm install -g ./openeden-1.0.0-dev.0.tgz
openeden
```

Other client commands include `openeden state` and
`openeden chat --message "Hello"`. An installed tarball requires Java 21+ and
a separately running server, but no Gradle or source checkout.
The package has not been published to the npm registry; registry installation
and `npx openeden` require a separate publication.

`.env` files are not loaded automatically. Export client settings in your shell
or use `node --env-file=.env npm/bin/openeden.mjs` from the source checkout.
LLM credentials, persona, models, memory and database configuration belong to
the separately managed server.

The package allowlist contains only CLI JVM libraries, the launcher and package
documentation. It excludes server libraries, persona, model artifacts,
credentials and runtime data. Review with `npm pack --dry-run --ignore-scripts`
after building.

import { createRequire } from 'node:module';

// Single source of truth for the version the bridge reports: package.json.
// createRequire resolves relative to this module, not the process cwd, so the
// bridge can be started from anywhere.
const require = createRequire(import.meta.url);

export const PACKAGE_VERSION = require('../package.json').version;
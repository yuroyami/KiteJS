// Runs one linked Kotlin/JS or Kotlin/Wasm test module in Node and reports every test.
// kotlin-test reports tests to Mocha-style describe/it functions when they exist, so this file defines them.
// Usage: node runner.mjs <module>_test.mjs. KITEJS_TEST_FILTER keeps only the tests whose name contains it.
import { createRequire } from "node:module";
import { pathToFileURL } from "node:url";

const root = { name: "", children: [], skipped: false };
let current = root;
const filter = process.env.KITEJS_TEST_FILTER ?? "";

function suite(name, body, skipped) {
  const node = { name, children: [], skipped: skipped || current.skipped };
  current.children.push(node);
  const parent = current;
  current = node;
  try {
    body();
  } finally {
    current = parent;
  }
}

globalThis.describe = (name, body) => suite(name, body, false);
globalThis.xdescribe = (name, body) => suite(name, body, true);
globalThis.it = (name, body) => current.children.push({ name, body, skipped: current.skipped });
globalThis.xit = (name, body) => current.children.push({ name, body, skipped: true });
// kotlin-test on Wasm reads this object and fails when it is missing. Gradle's Node runner defines it too.
globalThis.kotlinTest ??= { adapterTransformer: (adapter) => adapter };
// The linked tests are ES modules, which have no require. kotlinx-io calls it to load fs, path and os.
globalThis.require ??= createRequire(import.meta.url);

const tests = await import(pathToFileURL(process.argv[2]).href);
// A Kotlin/Wasm test module registers its tests only when its entry point runs.
if (typeof tests.startUnitTests === "function") tests.startUnitTests();

const counts = { passed: 0, failed: 0, ignored: 0 };

async function run(node, path) {
  for (const child of node.children) {
    const name = path ? `${path}.${child.name}` : child.name;
    if (child.children) {
      await run(child, name);
    } else if (!name.includes(filter)) {
      continue;
    } else if (child.skipped) {
      counts.ignored++;
    } else {
      try {
        // An asynchronous test, such as one that uses runTest, returns a promise.
        await child.body();
        counts.passed++;
      } catch (error) {
        counts.failed++;
        console.log(`Failed ${name}: ${error?.stack ?? error}`);
      }
    }
  }
}

await run(root, "");
console.log(`${counts.passed} passed, ${counts.failed} failed, ${counts.ignored} ignored`);
process.exitCode = counts.failed > 0 || counts.passed === 0 ? 1 : 0;

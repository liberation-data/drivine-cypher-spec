'use strict';
/**
 * The corpus check on wasmJs: each request of the JVM's corpus (build/oracle/corpus.jsonl) compiled by the npm package
 * through its CommonJS entry, and its answer compared with the JVM's, byte for byte.
 *
 *   node check/corpus-node.cjs <npm package directory> <corpus.jsonl> [<differences.jsonl>]
 */
const fs = require('fs');
const { performance } = require('perf_hooks');
const { report } = require('./report.cjs');

const [packageDir, corpus, differencesFile] = process.argv.slice(2);
const started = performance.now();
const { compile } = require(require('path').resolve(packageDir));
compile(JSON.stringify({ store: 'postgres', graph: { name: 'g' }, cypher: 'RETURN 1' }));
const loadMs = performance.now() - started;
const lines = fs.readFileSync(corpus, 'utf8').trim().split('\n').map((line) => JSON.parse(line));
const results = lines.map(({ request, answer }) => {
    const start = performance.now();
    const got = compile(request);
    return { request, answer, got, ms: performance.now() - start };
});
process.exit(report(`wasmJs (Node ${process.version})`, results, loadMs, differencesFile));

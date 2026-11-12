'use strict';
/**
 * The corpus check on wasmJs: each request of the JVM's corpus (build/oracle/corpus.jsonl) compiled by the npm package
 * through its CommonJS entry, and its answer compared with the JVM's, byte for byte. Then the same of the mutants
 * (build/oracle/mutants.jsonl), where given.
 *
 *   node check/corpus-node.cjs <npm package directory> <corpus.jsonl> [<differences.jsonl> [<mutants.jsonl> <their differences.jsonl>]]
 *
 * With DRIVINE_NPM_NAME set, as the Gradle task sets it from gradle.properties, the package must have that name.
 */
const fs = require('fs');
const { performance } = require('perf_hooks');
const { report } = require('./report.cjs');

const [packageDir, corpus, differencesFile, mutants, mutantDifferencesFile] = process.argv.slice(2);
const packageJson = JSON.parse(fs.readFileSync(require('path').join(packageDir, 'package.json'), 'utf8'));
if (process.env.DRIVINE_NPM_NAME && packageJson.name !== process.env.DRIVINE_NPM_NAME) {
    console.error(`The npm package is named ${packageJson.name}, not ${process.env.DRIVINE_NPM_NAME} as gradle.properties names it.`);
    process.exit(1);
}
// Every file the package's package.json names (its entries, and the types of each) is in the package.
const named = [];
(function walk(value) {
    if (typeof value === 'string') named.push(value);
    else if (value) Object.values(value).forEach(walk);
})((({ main, types, exports }) => ({ main, types, exports }))(packageJson));
const absent = named.filter((file) => !fs.existsSync(require('path').join(packageDir, file)));
if (absent.length > 0) {
    console.error(`The npm package's package.json names files it hasn't: ${absent.join(', ')}`);
    process.exit(1);
}
const started = performance.now();
const { compile, hostVersion } = require(require('path').resolve(packageDir));
compile(JSON.stringify({ store: 'postgres', graph: { name: 'g' }, cypher: 'RETURN 1' }));
const loadMs = performance.now() - started;
// The host functions' version the package gives is the one host.yaml pins (the JVM's host.json, beside the corpus).
const host = JSON.parse(fs.readFileSync(require('path').join(require('path').dirname(corpus), 'host.json'), 'utf8'));
const pinned = host.cases.find((c) => c.name === 'drivine_host_version').steps[0].result;
if (hostVersion() !== pinned) {
    console.error(`The npm package's hostVersion() is ${hostVersion()}, not ${pinned} as host.yaml's drivine_host_version pins it.`);
    process.exit(1);
}
const run = (file) =>
    fs.readFileSync(file, 'utf8').split('\n').filter((line) => line.trim()).map((line) => {
        const { request, answer } = JSON.parse(line);
        const start = performance.now();
        const got = compile(request);
        return { request, answer, got, ms: performance.now() - start };
    });
const target = `wasmJs (Node ${process.version})`;
let exit = report(target, run(corpus), loadMs, differencesFile);
if (mutants) exit |= report(target, run(mutants), loadMs, mutantDifferencesFile, 'Mutants');
process.exit(exit);

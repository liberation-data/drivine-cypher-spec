'use strict';
/**
 * The compiler's CommonJS entry point: the Kotlin/Wasm build, loaded synchronously.
 *
 * Kotlin/Wasm writes only an ES module loader, whose `instantiate` is async and which an ES module imports with a
 * top-level await, so `require` can't load it. Its Node branch is synchronous all the same (`new WebAssembly.Module`,
 * `new WebAssembly.Instance`), so this runs the loader's text as a function, with that branch given the module's bytes
 * and its awaits taken out. Each edit is checked to apply, so a Kotlin release that writes the loader otherwise fails
 * here at once, rather than loading something else.
 *
 *   const { compile } = require('@liberation-data/drivine-cypher-compiler');
 *   JSON.parse(compile(JSON.stringify({ store: 'postgres', graph: { name: 'g' }, cypher: 'RETURN 1' })));
 */
const fs = require('fs');
const path = require('path');
const url = require('url');

const NAME = 'drivine-cypher-compiler';
let loaded;

function edit(text, pattern, replacement, what) {
    const edited = text.replace(pattern, replacement);
    if (edited === text) {
        throw new Error(`${NAME}: the Kotlin/Wasm loader has no ${what} to edit: see index.cjs.`);
    }
    return edited;
}

function load() {
    if (loaded) {
        return loaded;
    }
    const loader = path.join(__dirname, `${NAME}.uninstantiated.mjs`);
    let source = fs.readFileSync(loader, 'utf8');
    source = edit(source, 'export async function instantiate', 'function instantiate', 'async instantiate');
    source = edit(
        source,
        /if \(isNodeJs\) \{\s*const module = await import\([^)]*\);[\s\S]*?wasmInstance = new WebAssembly\.Instance\(wasmModule, importObject\);\s*\}/,
        'if (isNodeJs) { wasmInstance = new WebAssembly.Instance(new WebAssembly.Module(__wasmBytes), importObject); }',
        'Node branch'
    );
    // The other branches (Deno, a browser) are never taken here, but must parse in a function that isn't async.
    source = source.replace(/await import\(/g, '(() => { throw new Error("not on Node"); })(');
    source = source.replace(/\bawait /g, '');
    source = source.replace(/import\.meta\.url/g, '__metaUrl').replace(/import\.meta/g, '({ url: __metaUrl, resolve: (p) => p })');
    const instantiate = new Function('__wasmBytes', '__metaUrl', `${source}\nreturn instantiate;`)(
        fs.readFileSync(path.join(__dirname, `${NAME}.wasm`)),
        url.pathToFileURL(loader).href
    );
    // The loader's own imports: kotlinx-datetime's, on wasmJs.
    loaded = instantiate({ '@js-joda/core': require('@js-joda/core') }).exports;
    return loaded;
}

/** A request as JSON text, and back the answer as JSON text: see the package's README. */
function compile(request) {
    return load().compile(request);
}

/**
 * The version of the SQLite host functions' forms the compiled SQL calls: a host checks `SELECT drivine_host_version()`
 * gives it on each connection it registers the functions on.
 */
function hostVersion() {
    return load().hostVersion();
}

module.exports = { compile, hostVersion };

'use strict';
const fs = require('fs');

/**
 * Prints how many of [results] match the JVM's answer, and their times; writes the differences. The exit code, 1 where
 * any differs or there are none. [what] names the cases: `Corpus`, or `Mutants`.
 */
function report(target, results, loadMs, differencesFile, what = 'Corpus') {
    if (results.length === 0) {
        console.log(`${what} on ${target}: no cases, so nothing is checked.`);
        return 1;
    }
    const differ = results.filter((r) => r.got !== r.answer);
    if (differencesFile) {
        fs.writeFileSync(differencesFile, differ.map((d) => JSON.stringify(d)).join('\n'));
    }
    const ms = results.map((r) => r.ms).sort((a, b) => a - b);
    const sum = ms.reduce((a, b) => a + b, 0);
    const at = (p) => ms[Math.min(ms.length - 1, Math.floor(p * ms.length))].toFixed(3);
    const slowest = results.slice().sort((a, b) => b.ms - a.ms)[0];
    console.log(
        `${what} on ${target}: ${results.length - differ.length} of ${results.length} answers byte-identical to the JVM's, ` +
            `${differ.length} differ. Load and first compile ${loadMs.toFixed(1)} ms; compile mean ${(sum / ms.length).toFixed(3)} ms, ` +
            `median ${at(0.5)} ms, p99 ${at(0.99)} ms, max ${ms[ms.length - 1].toFixed(1)} ms (${JSON.parse(slowest.request).cypher.slice(0, 80)}).`
    );
    for (const d of differ.slice(0, 5)) {
        console.log(`  differs: ${d.request.slice(0, 300)}\n    JVM:  ${d.answer.slice(0, 300)}\n    here: ${d.got.slice(0, 300)}`);
    }
    return differ.length === 0 ? 0 : 1;
}

module.exports = { report };

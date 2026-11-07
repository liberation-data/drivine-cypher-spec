#!/usr/bin/env bash
# The sync's own test: scripts/sync-from-drivine4j.sh and scripts/sync-report.py run over a made-up drivine4j and a copy
# of the module's scripts, and what they leave checked. No network, no build.
#
#   compiler/scripts/test-sync.sh
set -euo pipefail

scripts="$(cd "$(dirname "$0")" && pwd)"
root="$(mktemp -d)"
trap 'rm -rf "$root"' EXIT
fail() { echo "test-sync: $*" >&2; exit 1; }

# A drivine4j of two commits: the compiler's package, a helper beside a mapped file in its package, and a test.
d4j="$root/drivine4j"
src="$d4j/src/main/kotlin/org/drivine"
mkdir -p "$src/cypher" "$src/backend/sqlite" "$src/backend/oracle" "$d4j/src/test/kotlin/org/drivine/cypher"
git -C "$root" init --quiet drivine4j
git -C "$d4j" config user.email test@example.com
git -C "$d4j" config user.name test
cat > "$src/cypher/Reader.kt" <<'EOF'
package org.drivine.cypher

import org.antlr.v4.runtime.CharStreams
import java.math.BigInteger

fun one() = 1
fun two() = 2
EOF
cat > "$src/cypher/Clash.kt" <<'EOF'
package org.drivine.cypher

fun clash() = java.time.LocalDate.now()
EOF
cat > "$src/cypher/Gone.kt" <<'EOF'
package org.drivine.cypher

fun gone() = 0
EOF
cp "$src/cypher/Gone.kt" "$src/cypher/Unported.kt"
cp "$src/cypher/Gone.kt" "$src/cypher/Edited.kt"
cat > "$src/backend/sqlite/SqliteSql.kt" <<'EOF'
package org.drivine.backend.sqlite

object SqliteSql { val round = SqliteRound.NAME }
EOF
cat > "$src/backend/sqlite/SqliteRound.kt" <<'EOF'
package org.drivine.backend.sqlite

object SqliteRound { const val NAME = "round" }
EOF
# drivine4j's exception, and a library file of which the module holds a part in store/: both renamed by the rewrites.
cat > "$src/cypher/Refuse.kt" <<'EOF'
package org.drivine.cypher

import org.drivine.DrivineException
import org.drivine.backend.oracle.OracleSql
import org.drivine.model.Stamps

fun refuse(): Nothing = throw DrivineException("no")
fun literal() = OracleSql.literal("a")
EOF
cat > "$src/backend/oracle/OracleSql.kt" <<'EOF'
package org.drivine.backend.oracle

object OracleSql { fun literal(text: String) = "'" + text + "'" }
EOF
git -C "$d4j" add -A && git -C "$d4j" commit --quiet -m one
old="$(git -C "$d4j" rev-parse HEAD)"

# The module, ported from it: the mechanical rewrites, and an edit of its own in Clash.kt and in SqliteSql.kt.
module="$root/module"
mkdir -p "$module/scripts" "$module/src/commonMain/kotlin/org/drivine/backend/sqlite"
cp "$scripts/sync-from-drivine4j.sh" "$scripts/sync-report.py" "$scripts/port-rewrites.sed" "$module/scripts/"
echo "$old" > "$module/DRIVINE4J"
cat > "$module/scripts/sync-paths.txt" <<'EOF'
# drivine4j path, module path
src/main/kotlin/org/drivine/cypher/ src/commonMain/kotlin/org/drivine/cypher/
src/main/kotlin/org/drivine/backend/sqlite/SqliteSql.kt src/commonMain/kotlin/org/drivine/backend/sqlite/SqliteSql.kt
EOF
ours="$module/src/commonMain/kotlin/org/drivine"
mkdir -p "$ours/cypher"
sed -E -f "$scripts/port-rewrites.sed" "$src/cypher/Reader.kt" > "$ours/cypher/Reader.kt"
sed -E -f "$scripts/port-rewrites.sed" "$src/cypher/Refuse.kt" > "$ours/cypher/Refuse.kt"
cp "$src/cypher/Gone.kt" "$ours/cypher/Gone.kt"
echo "fun edited() = 1" >> "$ours/cypher/Edited.kt"
cat > "$ours/cypher/Clash.kt" <<'EOF'
package org.drivine.cypher

fun clash() = kotlinx.datetime.LocalDate(1970, 1, 1)
EOF
cat > "$ours/backend/sqlite/SqliteSql.kt" <<'EOF'
package org.drivine.backend.sqlite

object SqliteSql { val round = "round" }
EOF

# drivine4j's change: a line beside the rewritten imports, a line the port edited, a file added, one deleted, one the
# module edited deleted and one the module never had deleted, the helper and a new test.
sed -i.bak 's/fun two() = 2/fun two() = 22/' "$src/cypher/Reader.kt"
sed -i.bak 's/LocalDate.now()/LocalDate.MIN/' "$src/cypher/Clash.kt"
rm "$src"/cypher/*.bak "$src/cypher/Gone.kt" "$src/cypher/Unported.kt" "$src/cypher/Edited.kt"
cat > "$src/cypher/Added.kt" <<'EOF'
package org.drivine.cypher

import com.fasterxml.jackson.databind.node.ObjectNode

fun added(node: ObjectNode) = node
EOF
sed -i.bak 's/"round"/"ROUND"/' "$src/backend/sqlite/SqliteRound.kt" && rm "$src"/backend/sqlite/*.bak
sed -i.bak 's/DrivineException("no")/DrivineException("no!")/' "$src/cypher/Refuse.kt" && rm "$src"/cypher/*.bak
sed -i.bak 's/text + /text.trim() + /' "$src/backend/oracle/OracleSql.kt" && rm "$src"/backend/oracle/*.bak
echo "class NewTest" > "$d4j/src/test/kotlin/org/drivine/cypher/NewTest.kt"
git -C "$d4j" add -A && git -C "$d4j" commit --quiet -m two
new="$(git -C "$d4j" rev-parse HEAD)"

set +e
out="$(DRIVINE4J_REPO="$d4j" "$module/scripts/sync-from-drivine4j.sh" "$new" 2>&1)"
status=$?
set -e
echo "$out"

[ "$status" -ne 0 ] || fail "a conflict leaves the sync's exit status 0"
[ "$(cat "$module/DRIVINE4J")" = "$new" ] || fail "DRIVINE4J is not the new commit"
grep -q '^import org.antlr.v4.kotlinruntime.CharStreams$' "$ours/cypher/Reader.kt" || fail "Reader.kt lost the port's rewritten import"
grep -q '^import com.ionspin.kotlin.bignum.integer.BigInteger$' "$ours/cypher/Reader.kt" || fail "Reader.kt lost bignum's import"
grep -q '^fun two() = 22$' "$ours/cypher/Reader.kt" || fail "Reader.kt didn't take drivine4j's change"
grep -q '<<<<<<<' "$ours/cypher/Reader.kt" && fail "Reader.kt conflicted, though only the rewrites touched its imports"
grep -q '<<<<<<<' "$ours/cypher/Clash.kt" || fail "Clash.kt merged, though drivine4j changed the line the port edited"
grep -q 'kotlinx.datetime.LocalDate(1970, 1, 1)' "$ours/cypher/Clash.kt" || fail "Clash.kt lost the port's side of the conflict"
echo "$out" | grep -q 'CONFLICTS, to resolve: src/commonMain/kotlin/org/drivine/cypher/Clash.kt' || fail "the conflict isn't listed"
[ ! -f "$ours/cypher/Gone.kt" ] || fail "Gone.kt, unedited and deleted in drivine4j, is still there"
[ -f "$ours/cypher/Edited.kt" ] || fail "Edited.kt, which the module edited, was deleted with drivine4j's"
echo "$out" | grep -q 'kept: src/commonMain/kotlin/org/drivine/cypher/Edited.kt' || fail "Edited.kt isn't listed as kept"
echo "$out" | grep -q 'Unported.kt' && fail "a file the module never had is listed"
grep -q '^import org.drivine.cypher.json.ObjectNode$' "$ours/cypher/Added.kt" || fail "Added.kt wasn't copied in with the rewrites"
echo "$out" | grep -q 'src/main/kotlin/org/drivine/backend/sqlite/SqliteRound.kt' ||
    fail "the report misses SqliteRound.kt, which SqliteSql.kt uses from its own package with no import"
echo "$out" | grep -q 'src/test/kotlin/org/drivine/cypher/NewTest.kt' || fail "the report misses the new test"
grep -q '<<<<<<<' "$ours/cypher/Refuse.kt" && fail "Refuse.kt conflicted, though only the rewrites renamed the exception"
grep -q '^fun refuse(): Nothing = throw CompilerException("no!")$' "$ours/cypher/Refuse.kt" ||
    fail "Refuse.kt didn't take drivine4j's change as the module's exception"
grep -q '^import org.drivine.cypher.store.OracleSql$' "$ours/cypher/Refuse.kt" || fail "Refuse.kt lost the store's import"
grep -q '^import org.drivine.cypher.store.Stamps$' "$ours/cypher/Refuse.kt" || fail "Refuse.kt lost the store's import of Stamps"
echo "$out" | sed -n '/check the replacement/,/^  [a-z]/p' | grep -q 'src/main/kotlin/org/drivine/backend/oracle/OracleSql.kt' ||
    fail "the report doesn't list OracleSql.kt, whose part the module holds in store/, as a replacement to check"
echo "test-sync: the sync merged, conflicted, copied, deleted, kept and reported as it should."

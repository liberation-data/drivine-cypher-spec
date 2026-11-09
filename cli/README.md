# The drivine CLI

`drivine` runs Cypher from a terminal, a file or a REPL against any store Drivine reaches: Neo4j, Memgraph, FalkorDB, Postgres, Oracle and SQLite. On a SQL store, Drivine compiles the Cypher to SQL. It takes cypher-shell's flags, output and file format, so `alias cypher-shell=drivine` works and an `apoc.export.cypher` file (format `cypher-shell`) pipes straight in.

This page is the CLI's contract, and `cli.yaml` holds its cases. Every build of the CLI meets both:

- the native binaries, built with Oracle GraalVM, Mandrel and Liberica NIK alike;
- the jlink/jpackage build on a bundled OpenJDK;
- any later port, such as a Node single executable on Drivine for TypeScript.

The contract is versioned with this repo's tags, as the Cypher spec is.

The reference is cypher-shell 2026.10.1, as Neo4j 2026.10.1 ships it. `record-cypher-shell.py` records what cypher-shell gives for the cases marked `cypherShell: true`, so "drop-in" is measured, not claimed.

## Running

```
drivine [flags] [cypher]
```

| Input | What runs |
|---|---|
| `cypher`, the one positional argument, or `-c CYPHER` | That text, as a file's text is run (see [Files](#files)), then exit. |
| `-f FILE`, `--file FILE` | The file's statements, then exit. |
| stdin, when it isn't a terminal or `--non-interactive` is given | Its statements, then exit. |
| none of these, stdin a terminal | The [REPL](#the-repl). |

Each statement runs in its own transaction, unless it falls between `:begin` and `:commit`.

Only one of `cypher`, `-c` and `-f` may be given. Given two, the CLI exits with 2. Given one, stdin isn't read.

## Connecting

### The address

`-a ADDRESS`, `--address ADDRESS` or `--uri ADDRESS` names the store, as a URI:

| Store | Address |
|---|---|
| Neo4j | `neo4j://host:port`, `bolt://host:port`, and their `+s` and `+ssc` forms, as cypher-shell takes them |
| Memgraph | `memgraph://host:port`, or a `bolt://` address with `--type memgraph` |
| FalkorDB | `falkordb://host:port` |
| Postgres | `postgresql://host:port/database` (`postgres://` too) |
| Oracle | `oracle://host:port/service` |
| SQLite | `sqlite:path`, or `sqlite::memory:` |

- **`--type TYPE`** names the store where the address leaves it open. The types are `neo4j`, `memgraph`, `falkordb`, `postgres`, `oracle` and `sqlite`.
- **With no address,** the CLI reads `NEO4J_ADDRESS`, then `NEO4J_URI`, then the profile. If none is set, it uses `neo4j://localhost:7687`, as cypher-shell does.
- **`-d DATABASE` or `--database DATABASE`, then `NEO4J_DATABASE`, then the profile** names the database, on a store that has more than one. On Postgres, a database named in the address wins.

### The user and the password

**The user** comes from `-u USERNAME` or `--username USERNAME`, then `NEO4J_USERNAME`, then the profile.

**The password** comes from the first of these that gives one:

1. `-p PASSWORD` or `--password PASSWORD`. These are taken so that a script calling `cypher-shell -u x -p y` keeps working. Each use prints one line to stderr: `drivine: a password given with -p shows in ps and in your shell history; use DRIVINE_PASSWORD, a profile or the prompt instead.`
2. `DRIVINE_PASSWORD`.
3. `NEO4J_PASSWORD`, which cypher-shell reads.
4. The profile.
5. A prompt, when stdin is a terminal. The prompt doesn't echo.

The recommended routes are `DRIVINE_PASSWORD`, the profile and the prompt. Where a store needs no password, as SQLite needs none, none is asked for.

### Profiles

`~/.drivine.yml` and `./.drivine.yml` hold named profiles. `--profile NAME` picks one. With no `--profile`, the CLI uses the profile named by the file's `default:` key, if it has one. A profile in `./.drivine.yml` replaces the one of the same name in `~/.drivine.yml`, as a whole, and its `default:` replaces `~/.drivine.yml`'s.

```yaml
default: local
profiles:
  local:
    address: "postgresql://localhost:5432/graphs"
    user: "drivine"
  prod:
    address: "oracle://db.example.com:1521/FREEPDB1"
    user: "graph"
    password: "…"
```

A profile's keys are `address`, `type`, `database`, `user` and `password`. Any other key is refused, with exit code 2.

Flags and environment variables override the profile, key by key. A `--profile` or a `default:` that names no profile is refused, with exit code 2.

## Flags

### cypher-shell's flags

The CLI takes every cypher-shell flag. Most do what they do in cypher-shell. The rest are taken and have no effect, or are refused.

| Flag | In drivine |
|---|---|
| `-a`, `--address`, `--uri` | The store's address: see [The address](#the-address). |
| `-u`, `--username` | As in cypher-shell. |
| `-p`, `--password` | As in cypher-shell, with a warning: see [The user and the password](#the-user-and-the-password). |
| `-d`, `--database` | As in cypher-shell. |
| `-f`, `--file` | As in cypher-shell: see [Files](#files). |
| `-P`, `--param` | A parameter: see [Parameters](#parameters). It may be given more than once. |
| `--format {auto,verbose,plain}` | As in cypher-shell, plus `json` and `csv`: see [Output](#output). |
| `--fail-fast`, `--fail-at-end` | As in cypher-shell. `--fail-at-end` is the same as `--continue`. `--fail-fast` given with either exits with 2. |
| `--non-interactive` | As in cypher-shell: no REPL, even on a terminal. |
| `--sample-rows N`, `--wrap {true,false}` | As in cypher-shell, for `verbose`. |
| `-v`, `--version` | Prints `drivine <version>` and exits with 0. |
| `--driver-version` | Prints the drivers' versions, one per line, and exits with 0. |
| `--history FILE` | As in cypher-shell. The default is `NEO4J_CYPHER_SHELL_HISTORY`, as cypher-shell reads it, and else `~/.drivine/history`. |
| `--log [FILE]`, `--debug [FILE]` | Logs to the file, or to stderr. |
| `--transaction-timeout T` | As in cypher-shell, where the store can time out a transaction. |
| `--idle-timeout T` | As in cypher-shell. |
| `--encryption`, `--access-mode`, `--impersonate` | Passed to the driver on Neo4j and Memgraph. On any other store, each is refused with exit code 2. |
| `--notifications`, `--enable-autocompletions`, `--error-format` | Taken, and have no effect. |
| `--change-password` | Refused, with exit code 2. |
| `-h`, `--help` | Prints the usage and exits with 0. |

### Drivine's own flags

| Flag | What it does |
|---|---|
| `-c CYPHER` | The Cypher to run, as the positional argument is. |
| `--type TYPE` | The store's type, where the address leaves it open. |
| `--profile NAME` | The profile to connect with. |
| `--continue` | Keep going past a failed statement, as `--fail-at-end` does. |
| `--explain` | On a SQL store, prints for each statement the SQL it compiles to, which may run over several lines, then one line, `-- parameters: ` and its parameters as compact JSON, written as `json` writes values. It runs no statement of the input, but it still connects to evaluate each `-P` and `:param` expression, as [Parameters](#parameters) says. The SQL is the compiler's, and cases don't compare it. On a graph engine, it's refused with exit code 2: there, the store runs the Cypher itself. |

Any other flag is refused, with exit code 2. The message names the flag and points to `--help`.

## Files

A file, stdin and the positional argument are read alike.

**Statements.** The text is split into statements on `;`. A `;` doesn't split inside these:
- a string in `'…'` or `"…"`, where `\` escapes the character after it;
- a name in backticks;
- a `//` comment, to the end of its line;
- a `/* … */` comment.

A statement that is only spaces and comments isn't run. The text after the last `;` is run too, as long as it is more than spaces and comments.

**Commands.** A line whose first character after any spaces is `:` is a command, where a statement would start: not inside a string, a comment, or a statement that no `;` has ended yet. It runs on its own, and needs no `;`:

| Command | What it does |
|---|---|
| `:begin` | Opens a transaction, which the statements after it run in. |
| `:commit` | Commits it. |
| `:rollback` | Rolls it back. |
| `:param NAME => EXPRESSION` | Sets a parameter, as `-P` does. |
| `:param {NAME: EXPRESSION, …}` | Sets the parameters named. |
| `:params` | Prints the parameters to stdout as a map over several lines: `{`, then each `name: value` on a line of its own, indented two spaces, with `,` after each but the last, then `}`. A text is in `'…'`, not `"…"` as `plain` prints it. |
| `:params clear` | Clears them. |
| `:use DATABASE` | Moves to another database, on a store that has more than one. |
| `:source FILE` | Runs a file's statements in place. A relative path is read from the working directory. |
| `:exit`, `:quit` | Stops reading. |
| `:help` | Lists the commands. |

Any other command fails as a statement fails, and prints `Could not find command :nope, use :help to see available commands` to stderr, `:nope` being the command, as cypher-shell does.

If the input ends inside a transaction, the transaction is rolled back, as cypher-shell does.

FalkorDB commits each statement as it runs, so there `:begin` and `:commit` do nothing, and `:rollback` prints a warning to stderr that nothing was rolled back, as Drivine's `TransactionMode.WARN` does.

## Parameters

`-P` and `:param` take cypher-shell's two forms:
- `NAME => EXPRESSION`, as in `-P 'limit => 10'`;
- a map, as in `-P '{limit: 10, since: date("2024-01-01")}'`.

`-P NAME=EXPRESSION` is taken too.

The expression is Cypher. It's evaluated by running `RETURN expression` on the store, so a parameter holds what the store gives for it. A parameter set later replaces an earlier one of the same name. A statement that uses a parameter that isn't set fails, on every store, as it does on Neo4j.

## Output

`--format` takes cypher-shell's three formats and two of Drivine's own:

| Format | What it prints |
|---|---|
| `auto` | `verbose` when stdout is a terminal, `plain` when it isn't. This is the default. |
| `verbose` | Each result as a table, then its count of rows and how long it took, then the store's counts of what it wrote, as cypher-shell prints them. A result with no columns, such as a write with no `RETURN`, prints no table. |
| `plain` | Each result's columns, `, ` between them, then a line per row, with the values printed as cypher-shell prints them. A result with no rows prints nothing. |
| `json` | One line per statement, compact: `{"columns":[...],"rows":[[...],...]}`. Values are written as the spec's files write them, with NaN and the infinities as `{"$float":"NaN"}` and the others. |
| `csv` | Each result as RFC 4180 CSV, with a header row, and lines that end in `\n`. A text is its own characters, null is an empty field, and any other value is its compact JSON. Results are separated by one empty line. |

**How `plain` and `verbose` print a value**, as cypher-shell does:

| Value | Printed |
|---|---|
| null | `NULL` |
| a boolean | `TRUE` or `FALSE` |
| a text | in `"…"`, with each `"` escaped as `\"`. A `\` is printed as it is: `'a"b\\c'` prints as `"a\"b\c"`. |
| a list | `[1, "b"]` |
| a map | `{k: 2}` |
| a node | `(:Label {key: value})` |
| a relationship | `[:TYPE {key: value}]` |
| a path | its nodes and relationships, as in `(:A)-[:R]->(:B)<-[:S]-(:C)`: `-[…]->` for a relationship walked along its direction, `<-[…]-` for one walked against it |

On a SQL store, the CLI reads a node's labels and a relationship's type along with their properties, though `PersistenceManager.query` gives only the properties, and prints them as it would on Neo4j.

**`verbose`'s timing line**, `ready to start consuming query after N ms, results consumed after another M ms`, varies from run to run. Cases don't compare it.

**Counts.** `verbose` prints the store's counts of what a statement wrote (`Created 1 node, deleted 1 node, added 1 label`) where the store gives them. A SQL store that gives none prints none.

**Errors** go to stderr. Each is the store's message, or Drivine's own: a refusal by the compiler, a failure to connect, a bad flag. A case compares Drivine's own messages, but not the store's.

## Failures and exit codes

**Stopping.** By default, the CLI stops at the first statement or command that fails, as `--fail-fast` does. With `--fail-at-end` or `--continue`, it runs everything and reports each failure as it comes.

| Exit code | When |
|---|---|
| 0 | Everything ran. |
| 1 | A statement or a command failed. |
| 2 | The invocation is wrong: an unknown or refused flag, a bad value, two inputs, a bad profile. Nothing ran. The invocation is checked before the CLI connects, so it exits with 2, not 3, where the store can't be reached too. |
| 3 | The store can't be reached, or it refused the user and password. Nothing ran. |

cypher-shell exits with 1 in all these cases. A script that tests for non-zero works with both, and a script that tests for 1 alone sees `drivine` exit with 2 or 3 where cypher-shell exited with 1.

## The REPL

The REPL starts when stdin is a terminal, no input was given, and `--non-interactive` wasn't set.

- **The prompt** is `drivine@NAME> `, where NAME is the profile, or else the store's type. Inside a transaction it is `drivine@NAME# `. A statement that runs over several lines shows `  > ` on each line after the first.
- **A statement** runs once a `;` ends it, outside a string or a comment. Commands run at once.
- **History** goes to `--history`'s file. `in-memory` keeps it for the session alone, and `disable` keeps none.
- **Leaving:** `:exit`, `:quit` or Ctrl-D leaves. Ctrl-C drops the statement being typed.

The REPL's output is `auto`'s, which tests stdout, not stdin: `verbose` when stdout is a terminal, `plain` when it is piped.

On Windows, where a pipe can look like a terminal, `--non-interactive` makes the CLI read stdin, as in cypher-shell.

## Cases

`cli.yaml` holds the cases each build runs. A runner runs each one against every store the case names, giving the CLI that store's address and credentials. The address goes in `-a`, and the credentials in `NEO4J_USERNAME` and `DRIVINE_PASSWORD`, unless the case gives its own. In `args` and `env`, `$user` and `$password` stand for the store's. The runner empties the graph before each case, and runs each case in an empty directory of its own, with `HOME` another empty directory, and with no `NEO4J_` variable or `DRIVINE_PASSWORD` set but those it gives.

A store in `only` and `except` is `neo4j`, `memgraph`, `falkordb`, `postgres`, `oracle` or `sqlite`: the CLI doesn't reach Neptune. `graph` names the first three, and `sql` the last three.

```yaml
- name: "plain prints each value as cypher-shell does"
  cypherShell: true
  args: ["--format", "plain", "RETURN 1 AS n, 'a' AS s"]
  stdout: "n, s\n1, \"a\"\n"
  exit: 0
```

| Key | What it is |
|---|---|
| `name` | Unique in the file. |
| `description` | What the case checks, where its name doesn't say it. Optional. |
| `args` | The arguments, after the address the runner gives. |
| `stdin` | Text fed to stdin. With no `stdin`, stdin is empty. Either way it isn't a terminal. |
| `files` | Files written into the run's own empty directory before the CLI runs, by name. |
| `env` | Environment variables set for the run. A variable the case gives as null is unset. |
| `stdout` | What stdout must be, exactly. |
| `stdoutLines` | Regular expressions that stdout's lines must match in order, each a line after the one the expression before it matched. The lines between them aren't checked. |
| `stderr` | Text stderr must contain. |
| `exit` | The exit code. |
| `store` | `false` for a case that runs with no store, where the runner gives no address. |
| `only`, `except` | The stores it runs on, or doesn't, as in the spec's other files. |
| `cypherShell` | `true` where the expected output is cypher-shell 2026.10.1's for the same invocation on Neo4j, as `record-cypher-shell.py` checks. |
| `note` | Why the case is there. It is not read. |

Lines of `verbose`'s timing line are taken out of stdout before it is compared.

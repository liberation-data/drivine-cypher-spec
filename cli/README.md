# The drivine CLI

`drivine` runs Cypher from a terminal, a file or a REPL against any store Drivine reaches: Neo4j, Memgraph, FalkorDB, Postgres, Oracle and SQLite. On a SQL store, Drivine compiles the Cypher to SQL. It reads cypher-shell's file format and prints values as cypher-shell prints them, so an `apoc.export.cypher` file (format `cypher-shell`) pipes straight in. Its flags, environment variables and exit codes are Drivine's own. A mode that takes cypher-shell's comes later: see [cypher-shell compatibility (later)](#cypher-shell-compatibility-later).

This page is the CLI's contract, and `cli.yaml` holds its cases. Every build of the CLI meets both:

- the native binaries, built with Oracle GraalVM, Mandrel and Liberica NIK alike;
- the jlink/jpackage build on a bundled OpenJDK;
- any later port, such as a Node single executable on Drivine for TypeScript.

The contract is versioned with this repo's tags, as the Cypher spec is.

The reference is cypher-shell 2026.10.1, as Neo4j 2026.10.1 ships it. `record-cypher-shell.py` checks what cypher-shell gives for the cases marked `cypherShell: true`, so where the CLI prints what cypher-shell prints, that is measured, not claimed.

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

Only one of `cypher`, `-c` and `-f` may be given, and only one positional argument. Given two, the CLI refuses them as a bad invocation (see [Failures and exit codes](#failures-and-exit-codes)). Given one, stdin isn't read.

## Connecting

### The address

`-a ADDRESS` or `--address ADDRESS`, then `DRIVINE_ADDRESS`, then the profile's `address`, names the store, as a URI:

| Store | Address |
|---|---|
| Neo4j | `neo4j://host:port`, `bolt://host:port`, and their `+s` and `+ssc` forms, as cypher-shell takes them |
| Memgraph | `memgraph://host:port`, or a `bolt://` address with `--type memgraph` |
| FalkorDB | `falkordb://host:port` |
| Postgres | `postgresql://host:port/database` (`postgres://` too), or with no `/database` |
| Oracle | `oracle://host:port/service`, or with no `/service` |
| SQLite | `sqlite:path`, or `sqlite::memory:` |

- **`--type TYPE`** names the store where the address leaves it open. The types are `neo4j`, `memgraph`, `falkordb`, `postgres`, `oracle` and `sqlite`. Only a `bolt://` address, in any of its forms, leaves the store open: with no type it is Neo4j. Every other scheme names its store, and a type the scheme contradicts, as `--type neo4j` with `postgresql://`, is refused as a bad invocation. So is an address whose scheme isn't in the table.
- **With no address** from any of these, there is no default: the CLI refuses the invocation, as a bad one, and says to give `-a`, `DRIVINE_ADDRESS` or a profile.

### The database and the graph

The database and the graph are kept apart. `-d DATABASE` or `--database DATABASE`, then `DRIVINE_DATABASE`, then the profile's `database`, names the database. `-g GRAPH` or `--graph GRAPH`, then `DRIVINE_GRAPH`, then the profile's `graph`, names the graph. Each store takes them as this table says:

| Store | `--database` | `--graph` |
|---|---|---|
| Neo4j, Memgraph | The database, by its name. | Refused. |
| FalkorDB | Refused. | The graph, by its name. |
| Postgres | The database. A database named in the address wins. | The Drivine graph within the database. |
| Oracle | The service, by its name. A service named in the address wins. | The Drivine graph. |
| SQLite | Refused: `-a sqlite:path` names the file. | The Drivine graph. |

A refused one is refused however it is given, as a flag, an environment variable or a profile's key, as a bad invocation. Where no graph is given, FalkorDB and the SQL stores use Drivine's default graph.

### The user and the password

**The user** comes from `-u USER`, `--user USER` or `--username USER`, then the address's user, as below, then `DRIVINE_USER`, then the profile's `user`.

**The password** comes from the first of these that gives one:

1. `DRIVINE_PASSWORD`.
2. The profile's `password`.
3. A prompt, when stdin is a terminal. The prompt doesn't echo.

When none of these gives a password and stdin isn't a terminal, nothing prompts, as in cypher-shell: the CLI connects with no password, and with no user if none is given, and a store that needs them refuses them, which fails as a connection does, with exit code 3. Where a store needs no password, as SQLite needs none, none is asked for.

A password is never taken on the command line. `-p` and `--password` are refused as a bad invocation, with one line on stderr: `drivine: a password on the command line shows in ps and in your shell history; use DRIVINE_PASSWORD, a profile or the prompt.` An address given with `-a` that holds a password, as `postgresql://user:secret@host/db` does, is refused so too, with the same line. A user in an address (`postgresql://user@host/db`) is taken, after `-u`. An address that holds a password may come from `DRIVINE_ADDRESS` or a profile, which are kept off the command line, and its password then comes after `DRIVINE_PASSWORD`, as a profile's does.

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

A profile's keys are `address`, `type`, `database`, `graph`, `user` and `password`. Any other key is refused, as a bad invocation.

A profile may hold the password, as `~/.pgpass` may. The CLI refuses to read a profile file that holds a `password` and that its group or others can read: it says so on stderr, names the file, gives the fix, `chmod 600 <file>`, and exits as a bad invocation does. On Windows, where a file has no such modes, it doesn't check.

Flags and environment variables override the profile, key by key. So `DRIVINE_PASSWORD` comes before the profile's `password`, as [the password's order](#the-user-and-the-password) says. A user or a database that the profile's `address` names is the address's, not a key's, and comes where [the user's order](#the-user-and-the-password) and [the table](#the-database-and-the-graph) put an address's: before `DRIVINE_USER` and `DRIVINE_DATABASE`. A `--profile` or a `default:` that names no profile is refused, as a bad invocation.

## Flags

| Flag | What it does |
|---|---|
| `-c CYPHER` | The Cypher to run, as the positional argument is: see [Running](#running). |
| `-f FILE`, `--file FILE` | The file to run: see [Files](#files). |
| `-a ADDRESS`, `--address ADDRESS` | The store's address: see [The address](#the-address). |
| `--type TYPE` | The store's type, where the address leaves it open. |
| `-d DATABASE`, `--database DATABASE` | The database: see [The database and the graph](#the-database-and-the-graph). |
| `-g GRAPH`, `--graph GRAPH` | The graph: see [The database and the graph](#the-database-and-the-graph). |
| `-u USER`, `--user USER`, `--username USER` | The user: see [The user and the password](#the-user-and-the-password). |
| `--profile NAME` | The profile to connect with: see [Profiles](#profiles). |
| `-P PARAM`, `--param PARAM` | A parameter: see [Parameters](#parameters). It may be given more than once. |
| `--format FORMAT` | `auto`, `table`, `plain`, `json` or `csv`. The default is `auto`. See [Output](#output). |
| `--sample-rows N` | For `table`, the number of rows sampled to size its columns. The default is 1000. |
| `--wrap {true,false}` | For `table`, whether a value wider than its column wraps. The default is `true`. |
| `--continue` | Keeps going past a failed statement or command: see [Failures and exit codes](#failures-and-exit-codes). |
| `--explain` | On a SQL store, prints for each statement the SQL it compiles to, which may run over several lines, then one line, `-- parameters: ` and the Cypher parameters the statement is given, by their names, as compact JSON, written as `json` writes values. These are the statement's own parameters, not the values the SQL binds. It runs no statement of the input, but it still connects to evaluate each `-P` and `:param` expression, as [Parameters](#parameters) says. The SQL is the compiler's, and cases don't compare it. On a graph engine, it's refused as a bad invocation: there, the store runs the Cypher itself. |
| `--non-interactive` | No REPL, even on a terminal: see [The REPL](#the-repl). |
| `--history FILE` | The REPL's history file, or `in-memory` or `disable`. The default is `~/.drivine/history`. |
| `--idle-timeout T` | Leaves the REPL after T without input, given as `<hours>h<minutes>m<seconds>s`, as in `1h30m`. |
| `--transaction-timeout T` | Times out a transaction after T, given as `--idle-timeout` gives it, where the store can time out a transaction. |
| `--log`, `--log=FILE` | Logs to stderr, or with `=FILE` to the file. The argument after `--log` is never its file. |
| `-v`, `--version` | Prints `drivine <version>` and exits with 0. |
| `--driver-version` | Prints the drivers' versions, one per line, and exits with 0. |
| `-h`, `--help` | Prints the usage and exits with 0. |

`-p` and `--password` are refused, as [The user and the password](#the-user-and-the-password) says. Any other flag is unknown, and so is a cypher-shell flag that isn't in this table, until the [compatibility mode](#cypher-shell-compatibility-later) takes it. A refused or unknown flag is a bad invocation. The message names the flag and points to `--help`, except for `-p` and `--password`, whose message is the one line that section gives.

## Files

A file, stdin and the positional argument are read alike.

**Statements.** The text is split into statements on `;`. A `;` doesn't split inside these:
- a string in `'…'` or `"…"`, where `\` escapes the character after it;
- a name in backticks;
- a `//` comment, to the end of its line;
- a `/* … */` comment.

A line may end in `\r\n` as well as `\n`: the `\r` is not part of a command. A statement that is only spaces and comments isn't run. The text after the last `;` is run too, as long as it is more than spaces and comments.

**Commands.** A line whose first character after any spaces is `:` is a command, where a statement would start: not inside a string, a comment, or a statement that no `;` has ended yet. It runs on its own, and needs no `;`:

| Command | What it does |
|---|---|
| `:begin` | Opens a transaction, which the statements after it run in. Inside one, it fails as a command does. |
| `:commit` | Commits it. With no transaction open, it fails as a command does. |
| `:rollback` | Rolls it back. With no transaction open, it fails as a command does. |
| `:param NAME => EXPRESSION` | Sets a parameter, as `-P` does. |
| `:param {NAME: EXPRESSION, …}` | Sets the parameters named. |
| `:params` | Prints the parameters to stdout as a map over several lines: `{`, then each `name: value` on a line of its own, indented two spaces, with `,` after each but the last, then `}`. A text is in `'…'`, not `"…"` as `plain` prints it. A map's parameters print in the order the store gives its keys, as **Order** in [Output](#output) says. |
| `:params clear` | Clears them. |
| `:use DATABASE` | Moves to another database, as `--database` names one in [the table](#the-database-and-the-graph), but over a database or a service the address names. Where `--database` is refused, it fails as a command does. |
| `:source FILE` | Runs a file's statements in place. A relative path is read from the working directory. A file that can't be read fails as a statement does, with exit code 1. |
| `:exit`, `:quit` | Stops reading. |
| `:help` | Lists the commands. |

Any other command fails as a statement fails, and prints `Could not find command :nope, use :help to see available commands` to stderr, `:nope` being the command, as cypher-shell does.

If the input ends inside a transaction, the transaction is rolled back, as cypher-shell does. The run doesn't fail for it.

FalkorDB has no multi-statement transactions, so no transaction is ever open there. There `:begin` prints one line to stderr, `FalkorDB has no multi-statement transactions; each statement runs and commits on its own`, and the run goes on, each statement committing as it runs. A `:begin` after another, as in an `apoc.export.cypher` file's batches, warns again and doesn't fail. `:commit` does nothing. `:rollback` fails as a command does, with a message on stderr and exit code 1, since nothing can be rolled back. An input that ends after `:begin` with no `:commit` rolls nothing back there, and doesn't fail.

## Parameters

`-P` and `:param` take cypher-shell's two forms:
- `NAME => EXPRESSION`, as in `-P 'limit => 10'`;
- a map, as in `-P '{limit: 10, since: date("2024-01-01")}'`.

`-P NAME=EXPRESSION` is taken too, where the text doesn't start with `{` and the first `=` isn't the start of a `=>`: a text that starts with `{` is a map, even one holding a `=`, as `{url: 'a?b=c'}` does. Spaces around the `=`, as around a `=>`, are part of neither the name nor the expression, so `-P 'limit = 10'` is `limit`. A `-P` of none of these forms is a bad value, and so a bad invocation.

The expression is Cypher. It's evaluated by running `RETURN expression` on the store, so a parameter holds what the store gives for it. A parameter set later replaces an earlier one of the same name. A statement that uses a parameter that isn't set fails, on every store, as it does on Neo4j. A `-P` whose expression fails on the store fails as a statement does, with exit code 1, and no statement runs, with `--continue` too: the statements would run without the parameter.

## Output

`--format` takes five formats:

| Format | What it prints |
|---|---|
| `auto` | `table` when stdout is a terminal, `plain` when it isn't. This is the default. |
| `table` | Each result as a table, then its count of rows and how long it took, then the store's counts of what it wrote, as cypher-shell's `verbose` prints them. A result with no columns, such as a write with no `RETURN`, prints no table. |
| `plain` | Each result's columns, `, ` between them, then a line per row, with the values printed as cypher-shell prints them. A result with no rows prints nothing. |
| `json` | One line per statement, compact: `{"columns":[...],"rows":[[...],...]}`. Values are written as the spec's files write them, with NaN and the infinities as `{"$float":"NaN"}` and the others, and a float as `plain` prints it: `2.0`, not `2`, and `1.0E10`. A whole number is written exactly, past 2^53 too. A text's characters are written as they are, escaped only where JSON requires it: `\"`, `\\`, `\b`, `\f`, `\n`, `\r` and `\t` in their short forms, and any other control character as `\u00XX`. A temporal value is its ISO 8601 text, as a JSON string. A node is `{"$t":"node","labels":[…],"properties":{…}}`, with its labels sorted, a relationship `{"$t":"relationship","type":…,"properties":{…}}`, and a path `{"$t":"path","nodes":[…],"relationships":[…],"forward":[…]}`, its nodes and relationships in the order it walks them, and `forward[i]` true where `relationships[i]` goes from `nodes[i]` to `nodes[i + 1]`, false where it goes the other way, one for each relationship. None holds the store's ids. `$t` is the kind, in the words of the spec's typed output (liberation-data/drivine-cypher-spec#161), so a program tells a node from a map without guessing: a map of the user's own that has a `$t` key is written inside one, `{"$t":"map","v":{…}}`, in a list or a map too, and so are a node's or a relationship's `properties` that have one; any other map is written as it is. That is the output's own form: `results.yaml` writes the same map as `{$map: {…}}`, the spec's notation for its cases. |
| `csv` | Each result as RFC 4180 CSV, with a header row, and lines that end in `\n`. A text and a temporal value are their own characters, null is an empty field, and any other value is its compact JSON, as `json` writes it, so `2.0` is `2.0`. A field is in `"…"` only when it holds a `,`, a `"` or a line break. Results are separated by one empty line. A result with no columns, such as a write with no `RETURN`, prints nothing, not even its separator. |

**How `plain` and `table` print a value**, as cypher-shell does:

| Value | Printed |
|---|---|
| null | `NULL` |
| a boolean | `TRUE` or `FALSE` |
| a float | as Java's `Double.toString` writes it: always with a fraction, `2.0`, not `2`, which is a whole number; the fewest digits that read back as the same double; an exponent below 10^-3 and from 10^7, as in `1.0E10` and `1.0E-4`; and `-0.0` with its sign, where the store keeps it |
| a whole number | exactly, past 2^53 too |
| a text | in `"…"`, with each `"` escaped as `\"`. A `\` and a line break are printed as they are: `'a"b\\c'` prints as `"a\"b\c"`. |
| a list | `[1, "b"]` |
| a map | `{k: 2}` |
| a node | `(:Label {key: value})` |
| a relationship | `[:TYPE {key: value}]` |
| a temporal value | its ISO 8601 text, unquoted, as the driver gives it. A SQL store gives a temporal value as text, so there it prints as a text. |
| a path | its nodes and relationships, as in `(:A)-[:R]->(:B)<-[:S]-(:C)`: `-[…]->` for a relationship walked along its direction, `<-[…]-` for one walked against it |

**Order.** A map's keys, and a node's or a relationship's properties, print in the order the store gives them, in every format, as cypher-shell prints them. Neo4j gives `{b: 1, a: 2, aa: 3}` as `{aa: 3, a: 2, b: 1}`, where the SQL stores keep a map's keys in the order they were written (the spec's "Map key order"), so no case compares two keys' order but `:params`' `a` before `b`, which every store gives in that order.

**Text and lines.** Every format writes UTF-8 and ends each line with `\n`, on Windows too.

**Labels.** A node's labels print sorted, in every format, as `results.yaml` writes them, so a node reads the same on every store. On a SQL store, the CLI reads a node's labels and a relationship's type along with their properties, through a library's call that gives a result's typed values (see `results.yaml`) rather than `PersistenceManager.query`, which gives only the properties, and prints them as it would on Neo4j. A SQL store refuses a path (liberation-data/drivine-cypher-spec#163).

**`table`'s timing line**, `ready to start consuming query after N ms, results consumed after another M ms`, varies from run to run. Cases don't compare it.

**Counts.** `table` prints the store's counts of what a statement wrote (`Created 1 node, deleted 1 node, added 1 label`) where the store gives them. A SQL store that gives none prints none.

**Errors** go to stderr. Each is the store's message, or Drivine's own: a refusal by the compiler, a failure to connect, a bad flag. A case compares Drivine's own messages, but not the store's.

## Failures and exit codes

**Stopping.** By default, the CLI stops at the first statement or command that fails. With `--continue`, it runs everything and reports each failure as it comes.

| Exit code | When |
|---|---|
| 0 | Everything ran. |
| 1 | A statement or a command failed. |
| 2 | The invocation is wrong: an unknown or refused flag, a bad value, two inputs, no address, a `-f` file that can't be read, a bad profile. Nothing ran. The invocation is checked before the CLI connects, so it exits with 2, not 3, where the store can't be reached too. |
| 3 | The store can't be reached, or it refused the user and password. Nothing ran. |

The flags are read first, in the order given, and the first that ends the run ends it with nothing else checked: an unknown or refused flag, or a flag's missing or bad value, exits with 2, and `-h`, `--help`, `-v`, `--version` or `--driver-version` exits with 0. Only then are the rest checked, such as two inputs, the address and the profile, so `drivine --nope` names `--nope`, not the missing address.

A script that tests for non-zero works with these codes as with cypher-shell's, which exits with 1 for every failure.

## The REPL

The REPL starts when stdin is a terminal, no input was given, and `--non-interactive` wasn't set.

- **The prompt** is `drivine@NAME> `, where NAME is the profile, or else the store's type. Inside a transaction it is `drivine@NAME# `. A statement that runs over several lines shows `  > ` on each line after the first.
- **A statement** runs once a `;` ends it, outside a string or a comment. Commands run at once.
- **History** goes to `--history`'s file. `in-memory` keeps it for the session alone, and `disable` keeps none.
- **Leaving:** `:exit`, `:quit` or Ctrl-D leaves. Ctrl-C drops the statement being typed.

With no `--format`, the REPL's output is `auto`'s, which tests stdout, not stdin: `table` when stdout is a terminal, `plain` when it is piped.

On Windows, where a pipe can look like a terminal, `--non-interactive` makes the CLI read stdin, as in cypher-shell.

## cypher-shell compatibility (later)

The CLI reads cypher-shell's files and prints values as cypher-shell does, but its flags, environment variables and exit codes are Drivine's own. A mode that takes cypher-shell's, so that `alias cypher-shell=drivine` works and a script written for cypher-shell runs unchanged, is a later issue, [liberation-data/drivine4j#720](https://github.com/liberation-data/drivine4j/issues/720). What differs today:

- **Flag names.** `drivine` takes `--user` beside `--username`, and has `--graph`, `--type`, `--profile`, `--continue` and `--explain`, which cypher-shell doesn't. cypher-shell's `--uri`, `--debug`, `--encryption`, `--access-mode`, `--impersonate`, `--notifications`, `--enable-autocompletions`, `--error-format` and `--change-password` are unknown flags.
- **`-p` and `--password`.** cypher-shell takes a password on the command line. `drivine` refuses it.
- **Environment variables.** `drivine` reads `DRIVINE_ADDRESS`, `DRIVINE_USER`, `DRIVINE_PASSWORD`, `DRIVINE_DATABASE` and `DRIVINE_GRAPH`. It doesn't read cypher-shell's `NEO4J_ADDRESS`, `NEO4J_URI`, `NEO4J_USERNAME`, `NEO4J_PASSWORD`, `NEO4J_DATABASE` or `NEO4J_CYPHER_SHELL_HISTORY`.
- **The address.** With no address, cypher-shell connects to `neo4j://localhost:7687`. `drivine` refuses the invocation.
- **`--fail-fast` and `--fail-at-end`.** `drivine` stops at the first failure with no flag, as `--fail-fast` does, and `--continue` does what `--fail-at-end` does. `--fail-fast` and `--fail-at-end` are unknown flags.
- **`verbose`.** `drivine`'s `table` is cypher-shell's `verbose`, and prints the same. `auto` picks `table` where cypher-shell picks `verbose`, so it prints the same too.
- **Exit codes.** cypher-shell exits with 1 for every failure. `drivine` exits with 2 for a bad invocation and 3 for a store it can't connect to.
- **A node's labels.** cypher-shell prints them in the order Neo4j gives them. `drivine` prints them sorted, so `(:Q:P)` there may be `(:P:Q)` here. No case marked `cypherShell: true` holds a node of two labels.
- **`-d` and `--graph`.** cypher-shell's `-d` names a Neo4j database. `drivine`'s names the database or the service, and is refused on FalkorDB and SQLite. A graph is named with `--graph`, which cypher-shell doesn't have.

The cases marked `mode: cypher-shell` are the mode's tests. Each also marked `cypherShell: true` keeps its expected output, its exit code among it, as cypher-shell gives it. The runner gives them the store's user and password in `DRIVINE_USER` and `DRIVINE_PASSWORD`, as it gives every case, so the mode reads those before `NEO4J_USERNAME` and `NEO4J_PASSWORD`. `record-cypher-shell.py` checks every case marked `cypherShell: true` against cypher-shell, in either mode.

## Cases

`cli.yaml` holds the cases each build runs. A runner runs each one against every store the case names, giving the CLI that store's address and credentials. The address goes in `-a`, in its store's own scheme, as `memgraph://` for Memgraph, since a `bolt://` one with no `--type` is Neo4j, and the credentials in `DRIVINE_USER` and `DRIVINE_PASSWORD`, each unless the case gives that variable itself, even as null. In `args`, `env` and the texts of `files`, `$user` and `$password` stand for the store's. The runner empties the graph before each case, and runs each case in an empty directory of its own, with `HOME` another empty directory, and with no `DRIVINE_` or `NEO4J_` variable set but those it gives. It skips a case marked `mode: cypher-shell` until the [compatibility mode](#cypher-shell-compatibility-later) exists.

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
| `files` | Files written into the run's own empty directory before the CLI runs, by name. Each is its text, or a map of its `text` and its `mode`, in octal, as in `{text: "…", mode: "600"}`. A file is written with mode 0644, which others can read, unless it gives a `mode`. |
| `env` | Environment variables set for the run. A variable the case gives as null is unset. |
| `stdout` | What stdout must be, exactly. A case with neither `stdout` nor `stdoutLines` doesn't check stdout. |
| `stdoutLines` | Regular expressions that stdout's lines must match in order, each found anywhere in its line unless `^` and `$` anchor it, each a line after the one the expression before it matched. The lines between them aren't checked. |
| `stderr` | Text stderr must contain. |
| `exit` | The exit code. |
| `store` | `false` for a case that runs with no store, where the runner gives no address. |
| `only`, `except` | The stores it runs on, or doesn't, as in the spec's other files. |
| `os` | The operating systems it runs on, of `linux`, `macos` and `windows`. Every one where it isn't given. |
| `cypherShell` | `true` where the expected output is cypher-shell 2026.10.1's for the same invocation on Neo4j, as `record-cypher-shell.py` checks. A case of the default mode that has it is one where drivine already gives what cypher-shell gives. |
| `mode` | `cypher-shell` for a case that runs only in the [compatibility mode](#cypher-shell-compatibility-later), which a runner skips until that mode exists. A case with no `mode` runs in the default mode. |
| `note` | Why the case is there. It is not read. |

Lines of `table`'s timing line are taken out of stdout before it is compared.

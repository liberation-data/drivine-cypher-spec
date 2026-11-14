/** A request as JSON text, and back the answer as JSON text: see the package's README. The types of `require`. */
export declare function compile(request: string): string;

/** The version of the SQLite host functions' forms the compiled SQL calls, which `SELECT drivine_host_version()` must give. */
export declare function hostVersion(): number;

/**
 * SQLite's host function of temporal arithmetic, `drivine_temporal_arithmetic(operator, x, y)`: `x` and `y` each a JSON text or
 * null, a number as its text; the tag it makes as JSON text, or null. Throws for a pair Cypher has no arithmetic of.
 */
export declare function temporalArithmetic(operator: string, x: string | null, y: string | null): string | null;

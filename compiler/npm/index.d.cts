/** A request as JSON text, and back the answer as JSON text: see the package's README. The types of `require`. */
export declare function compile(request: string): string;

/** The version of the SQLite host functions' forms the compiled SQL calls, which `SELECT drivine_host_version()` must give. */
export declare function hostVersion(): number;

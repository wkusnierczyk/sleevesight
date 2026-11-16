// Gradle's dependency verification (#1034), held on (#1329): a change that turns it off or loosens
// it fails here, in every CI run, Gradle or not. Node 22 or later:
// node --test .github/scripts/dependency-verification.test.mjs
//
// Two checks:
//
// 1. `gradle/verification-metadata.xml`'s settings are pinned byte for byte, as `page-csp.test.mjs`
//    pins the pages' policy: signatures and metadata verified, the armored keyring, no key server,
//    and only sources and Javadoc jars trusted without a check. Changing them means changing the pin
//    here too, in `.github/`, which a review sees (`CODEOWNERS`). The trusted keys change with each
//    refresh (`tools/refresh-dependency-verification.sh`), so they are not pinned; each must be bound
//    to a group by name, as the refresh leaves them, never to a pattern or to every group. The rest
//    of the file must keep the shapes Gradle writes, line by line, so that nothing outside the pinned
//    lines can change a setting: no second `<configuration>`, comment, entity or other element. A
//    refresh that writes a new shape fails here; add it below if it loosens nothing.
// 2. No file Gradle or CI reads sets the verification mode: `org.gradle.dependency.verification`
//    (`gradle.properties`, a `-D` flag, `GRADLE_OPTS`), `--dependency-verification` or `-F`,
//    `--write-verification-metadata` or `-M` (a build writing the metadata checks nothing), the
//    start parameter's mode in a build script, or the functional tests' variable for writing it
//    (`Probe`). The workflows also must not name the verification files. Every line of every tracked
//    file is read, comments too, but prose (Markdown, `docs/`, `design/`, the release notes);
//    properties files are read entry by entry as Java reads them. The few lines that name the mode on
//    purpose are listed below.
//
// The guard is for a change made in good faith. A determined one can write the setting so that no
// pattern here finds it (built from pieces in a script); review is the guard against that.
//
// Not checked here: the checksums and keys a refresh records, which it trusts on first use (review
// its diff). A version with no entry fails `checkVerificationMetadata`, and the functional tests check
// that a nested build refuses a file the metadata does not cover (`ProbeTest`).

import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';

const ROOT = new URL('../../', import.meta.url);
const METADATA = 'gradle/verification-metadata.xml';

// 1. The metadata.

// The file's start, through its settings, up to the ignored and trusted keys.
const PINNED = [
  '<?xml version="1.0" encoding="UTF-8"?>',
  '<verification-metadata xmlns="https://schema.gradle.org/dependency-verification" ' +
    'xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" ' +
    'xsi:schemaLocation="https://schema.gradle.org/dependency-verification ' +
    'https://schema.gradle.org/dependency-verification/dependency-verification-1.4.xsd">',
  '   <configuration>',
  '      <verify-metadata>true</verify-metadata>',
  '      <verify-signatures>true</verify-signatures>',
  '      <keyring-format>armored</keyring-format>',
  '      <key-servers enabled="false"/>',
  '      <trusted-artifacts>',
  '         <trust file=".*-javadoc[.]jar" regex="true"/>',
  '         <trust file=".*-sources[.]jar" regex="true"/>',
  '      </trusted-artifacts>',
];

// Names as Gradle matches them literally: a group, a module, a version. No quote, space, `&` or `<`.
const NAME = '[A-Za-z0-9_.+-]+';
const TEXT = '[^"&<>]*';
const KEY = '[0-9A-F]{40}';
const named = `group="${NAME}"(?: name="${NAME}"(?: version="${NAME}")?)?`;
const line = (source) => new RegExp(`^${source}$`);

const SHAPES = {
  ignoredKey: line(`         <ignored-key id="[0-9A-F]{16,40}" reason="${TEXT}"/>`),
  // A key trusted for one group, or one module or version of it.
  trustedKey: line(`         <trusted-key id="${KEY}" ${named}/>`),
  // A key trusted for several, one `<trusting>` each.
  trustedKeyOpen: line(`         <trusted-key id="${KEY}">`),
  trusting: line(`            <trusting ${named}/>`),
  componentOpen: line(`      <component group="${NAME}" name="${NAME}" version="${NAME}">`),
  artifactOpen: line(`         <artifact name="[^"&<>/\\\\]+">`),
  sha256: line(`            <sha256 value="[0-9a-f]{64}" origin="${TEXT}"(?: reason="${TEXT}")?/>`),
  pgp: line(`            <pgp value="[0-9A-F]{16,40}"/>`),
};

// What is wrong with the metadata's text, as a list of reasons; the first one stops the reading.
export function metadataProblems(text) {
  if (!text.endsWith('\n')) return ['the file does not end in a line break'];
  const lines = text.slice(0, -1).split('\n');
  let at = 0;
  const shown = () => `line ${at + 1}: ${JSON.stringify(lines[at] ?? '(end of file)')}`;
  const take = (expected) => (lines[at] === expected ? (at++, true) : false);
  const fits = (shape) => (lines[at] !== undefined && shape.test(lines[at]) ? (at++, true) : false);

  for (const expected of PINNED) {
    if (!take(expected)) return [`not the pinned settings at ${shown()}; expected ${JSON.stringify(expected)}`];
  }
  if (take('      <ignored-keys>')) {
    while (fits(SHAPES.ignoredKey));
    if (!take('      </ignored-keys>')) return [`not an ignored key at ${shown()}`];
  }
  if (!take('      <trusted-keys>')) return [`no <trusted-keys> after the pinned settings at ${shown()}`];
  while (lines[at] !== '      </trusted-keys>') {
    if (fits(SHAPES.trustedKey)) continue;
    if (!fits(SHAPES.trustedKeyOpen)) return [`not a key trusted for a group by name at ${shown()}`];
    if (!fits(SHAPES.trusting)) return [`a trusted key with no group at ${shown()}`];
    while (fits(SHAPES.trusting));
    if (!take('         </trusted-key>')) return [`not a group by name at ${shown()}`];
  }
  at++;
  if (!take('   </configuration>')) return [`not the end of the settings at ${shown()}`];
  if (!take('   <components>')) return [`no <components> after the settings at ${shown()}`];
  while (lines[at] !== '   </components>') {
    if (!fits(SHAPES.componentOpen)) return [`not a component at ${shown()}`];
    do {
      if (!fits(SHAPES.artifactOpen)) return [`not an artifact at ${shown()}`];
      if (!fits(SHAPES.sha256) && !fits(SHAPES.pgp)) return [`an artifact with no checksum or key at ${shown()}`];
      while (fits(SHAPES.sha256) || fits(SHAPES.pgp));
      if (!take('         </artifact>')) return [`not a checksum or key at ${shown()}`];
    } while (lines[at] !== '      </component>');
    at++;
  }
  at++;
  if (!take('</verification-metadata>')) return [`not the end of the file at ${shown()}`];
  if (at !== lines.length) return [`more after the end at ${shown()}`];
  return [];
}

const read = (path) => readFileSync(new URL(path, ROOT), 'utf8');
const REAL = read(METADATA);

test('the verification metadata keeps the pinned settings, and keys trusted for groups by name', () => {
  assert.deepEqual(metadataProblems(REAL), []);
});

// Changes the check must refuse, made to the real file, each with the reason it is refused for.
const swap = (from, to) => {
  assert.equal(REAL.split(from).length, 2, `once in the file: ${from}`);
  return REAL.replace(from, to);
};
const SETTINGS = 'not the pinned settings';
const FIRST_KEY = /\n( {9}<trusted-key id="[0-9A-F]{40}" group="[^"]+")\/>\n/.exec(REAL)?.[1] ?? '(no key)';
const COMPONENTS = '   <components>\n';

const refused = {
  // The settings.
  'signatures not verified': [() => swap('<verify-signatures>true', '<verify-signatures>false'), SETTINGS],
  'metadata not verified': [() => swap('<verify-metadata>true', '<verify-metadata>false'), SETTINGS],
  'key servers on': [() => swap('<key-servers enabled="false"/>', '<key-servers enabled="true"/>'), SETTINGS],
  'another keyring format': [() => swap('<keyring-format>armored', '<keyring-format>binary'), SETTINGS],
  'a trusted artifact added': [
    () => swap('      </trusted-artifacts>', '         <trust group="com.example"/>\n      </trusted-artifacts>'),
    SETTINGS,
  ],
  'a trusted artifact widened': [() => swap('.*-javadoc[.]jar', '.*'), SETTINGS],
  'a comment around a setting': [
    () => swap('      <verify-signatures>true</verify-signatures>', '      <!-- <verify-signatures>true</verify-signatures> -->'),
    SETTINGS,
  ],
  'a document type, which could define entities': [
    () => swap('<verification-metadata xmlns=', '<!DOCTYPE x [<!ENTITY t "false">]>\n<verification-metadata xmlns='),
    SETTINGS,
  ],
  'a byte order mark': [() => '\uFEFF' + REAL, SETTINGS],
  'Windows line breaks': [() => REAL.replaceAll('\n', '\r\n'), SETTINGS],
  // The trusted keys.
  'a key trusted for a pattern of groups': [
    () => swap(`${FIRST_KEY}/>`, `${FIRST_KEY.replace(/group="[^"]+"/, 'group="^com[.].*"')} regex="true"/>`),
    'by name',
  ],
  'a key trusted for every group': [() => swap(`${FIRST_KEY}/>`, FIRST_KEY.replace(/ group="[^"]+"/, '') + '/>'), 'by name'],
  'a key trusted for a file pattern': [() => swap(`${FIRST_KEY}/>`, `${FIRST_KEY} file=".*"/>`), 'by name'],
  'a key trusted for a block of no group': [
    () => swap(`${FIRST_KEY}/>`, `${FIRST_KEY.replace(/ group="[^"]+"/, '>')}\n         </trusted-key>`),
    'no group',
  ],
  'a trusting pattern in a block': [
    () => REAL.replace(/( {12}<trusting group="[^"]+")\/>/, '$1 regex="true"/>'),
    'group',
  ],
  'a short key id': [() => swap(`${FIRST_KEY}/>`, FIRST_KEY.replace(/id="[0-9A-F]{24}/, 'id="') + '/>'), 'by name'],
  'an entity in a group': [() => swap(`${FIRST_KEY}/>`, FIRST_KEY.replace(/group="/, 'group="&x;') + '/>'), 'by name'],
  // Elsewhere in the file, where Gradle would read a setting too.
  'a second configuration after the components': [
    () => swap('</verification-metadata>', '<configuration><verify-signatures>false</verify-signatures></configuration>\n</verification-metadata>'),
    'not the end of the file',
  ],
  'settings among the components': [
    () => swap(COMPONENTS, `${COMPONENTS}      <trusted-artifacts><trust group=".*" regex="true"/></trusted-artifacts>\n`),
    'not a component',
  ],
  'a comment among the components': [() => swap(COMPONENTS, `${COMPONENTS}      <!-- x -->\n`), 'not a component'],
  'an artifact with no checksum': [
    () => REAL.replace(/(\n {9}<artifact name="[^"]+">\n) {12}<sha256 [^\n]+\n/, '$1'),
    'no checksum',
  ],
  'a weaker checksum': [() => REAL.replace(/<sha256 value="[0-9a-f]{64}"/, '<sha1 value="0000000000000000000000000000000000000000"'), 'no checksum'],
  'more after the end': [() => REAL + '<configuration/>\n', 'more after the end'],
  'no line break at the end': [() => REAL.slice(0, -1), 'line break'],
  'no metadata': [() => '', 'line break'],
};

// Each is made when its test runs, so that a real file the changes no longer fit fails those tests
// alone.
for (const [name, [change, reason]] of Object.entries(refused)) {
  test(`the metadata check refuses ${name}`, () => {
    const found = metadataProblems(change());
    assert.ok(found.some((problem) => problem.includes(reason)), `${name}: ${JSON.stringify(found)}`);
  });
}

// 2. Overrides of the verification mode.

// What sets the mode, turns verification off, or makes a build write the metadata rather than check
// it, in any file read. Gradle 9.9's public API has these ways (`StartParameter`,
// `ResolutionStrategy`, `StartParameterBuildOptions`); the internal one is its override, which a
// build reaching into Gradle's services could replace. Not here, since neither accepts a file the
// metadata does not: `refreshKeys` and `exportKeys` (keys still need trusting, and the key servers
// are off), and lenient resolution (`ArtifactView.lenient`, `lenientConfiguration`), which leaves a
// file that fails verification out rather than taking it.
const MODE = [
  /dependency[.]verification/i, // `org.gradle.dependency.verification`, in a properties file or `-D`.
  /--dependency-verification/i,
  /dependencyverificationmode/i, // `StartParameter`'s mode (`setDependencyVerificationMode`, the enum).
  /disabledependencyverification/i, // `ResolutionStrategy.disableDependencyVerification()`, per configuration.
  /verificationoverride/i, // Gradle's internal `DependencyVerificationOverride` and its kinds.
  /write[-_]?dependency[-_]?verification/i, // `StartParameter`'s writing of the metadata.
  /write[-_]verification[-_]metadata/i, // `--write-verification-metadata`; `Probe`'s variable.
];
// In what CI runs (`WORKFLOW_PATHS`: the workflows, their actions and scripts), also the files
// themselves, which a step could remove or replace (with no metadata, Gradle verifies nothing), and
// the refresh, which writes the metadata again from whatever it downloads (`reset` turns the key
// servers on). A workflow's `paths:` filter naming a file fails too: telling it from a step would
// need the YAML's structure, which nothing here parses; such a line goes in `ALLOWED`.
const WORKFLOW = [
  /verification-metadata/i,
  /verification-keyring/i,
  /refresh-dependency-verification|dependency-verification[.]py/i,
];
const WORKFLOW_PATHS = /^[.]github\/(workflows|actions|scripts)\//;
// The short options for the mode (`-F`) and for writing the metadata (`-M`), anywhere they could be
// an option, whatever runs them: a Gradle command can be folded over lines in YAML, continued in a
// shell, or put together from a variable set elsewhere, so no command is parsed. An option counts
// when a value Gradle takes follows it (a mode, a checksum kind, in any case), or a variable (`$`),
// or nothing to the end of the line (the value on a folded or continued line). Other tools' options
// (`gh api -F per_page=100`, `curl -F "file=@x"`, `git diff -M --no-abbrev`) and code (`-Float`,
// `-MAX`) have none of these after them; a value must end there (`-F offset=10` is not `off`).
const anyCase = (word) => word.replace(/[a-z]/g, (c) => `[${c}${c.toUpperCase()}]`);
const VALUE = `(?:(?:${['off', 'lenient', 'strict', 'pgp', 'sha[0-9]+', 'md5'].map(anyCase).join('|')})(?![\\w-])|\\$)`;
const SHORT = new RegExp(`(^|[\\s"'\`=(\\[\\]{},;|&])-[FM](?=[\\s"'\`=,\\\\\\])}]*$|[\\s"'\`=,]*${VALUE})`);

// The lines that name these on purpose, by file, as they read (trimmed).
const ALLOWED = {
  '.github/workflows/dependency-graph.yml': [
    "# here: `dependency-submission` runs Gradle with `-Dorg.gradle.dependency.verification=off`. The",
  ],
  'build-logic/src/functionalTest/kotlin/sleevesight/Probe.kt': [
    '* With `SLEEVESIGHT_WRITE_VERIFICATION_METADATA` set to a mode (`pgp,sha256`, `sha256`), as',
    'private val writeMode = System.getenv("SLEEVESIGHT_WRITE_VERIFICATION_METADATA")?.takeIf { it.isNotBlank() }',
    'val write = writeMode?.let { listOf("--write-verification-metadata", it, "--export-keys") }.orEmpty()',
  ],
  'build-logic/src/main/kotlin/sleevesight.locking.gradle.kts': [
    '// `--write-verification-metadata` records them (`design/development.md`, *Dependencies*). A',
  ],
  'tools/refresh-dependency-verification.sh': [
    './gradlew --console=plain --write-verification-metadata pgp,sha256 --export-keys --write-locks "${tasks[@]}"',
    './gradlew --console=plain --write-verification-metadata sha256 --write-locks "${tasks[@]}"',
    'SLEEVESIGHT_WRITE_VERIFICATION_METADATA=pgp,sha256 ./gradlew --console=plain :build-logic:functionalTest --rerun',
    'SLEEVESIGHT_WRITE_VERIFICATION_METADATA=sha256 ./gradlew --console=plain :build-logic:functionalTest --rerun',
  ],
};

// Prose, which no build or workflow reads, and this file, which names every pattern.
const PROSE = /(^|\/)[^/]*\.md$|^docs\/|^design\/|^release-notes\//;
const SELF = '.github/scripts/dependency-verification.test.mjs';

// A properties file's entries as `java.util.Properties` reads them (Gradle reads `gradle.properties`
// with it), each as `key=value` with its first line's number. A line whose first character past
// spaces, tabs and form feeds is `#` or `!` is a comment, and is never continued; any other line
// ending in an odd number of backslashes goes on in the next, less that line's leading blanks. The
// key ends at the first `=`, `:` or blank not escaped; in key and value, `\uXXXX` is a character,
// `\t`, `\n`, `\r` and `\f` what they are in Java, and a backslash before anything else that thing.
export function properties(text) {
  const lines = text.split(/\r\n|\r|\n/);
  const blanks = /^[ \t\f]*/;
  const entries = [];
  for (let i = 0; i < lines.length; i++) {
    const first = i + 1;
    let line = lines[i].replace(blanks, '');
    if (line === '' || line[0] === '#' || line[0] === '!') continue;
    let logical = '';
    for (;;) {
      const continued = /\\*$/.exec(line)[0].length % 2 === 1;
      logical += continued ? line.slice(0, -1) : line;
      if (!continued || i + 1 >= lines.length) break;
      line = lines[++i].replace(blanks, '');
    }
    let end = 0;
    while (end < logical.length && !'=: \t\f'.includes(logical[end])) end += logical[end] === '\\' ? 2 : 1;
    const rest = logical.slice(end).replace(/^[ \t\f]*(?:[=:][ \t\f]*)?/, '');
    entries.push({ line: first, text: `${unescape(logical.slice(0, end))}=${unescape(rest)}` });
  }
  return entries;
}

const ESCAPES = { t: '\t', n: '\n', r: '\r', f: '\f' };
const unescape = (text) =>
  text.replace(/\\(u[0-9A-Fa-f]{4}|[\s\S]?)/g, (_, escaped) =>
    escaped.length === 5 ? String.fromCodePoint(parseInt(escaped.slice(1), 16)) : (ESCAPES[escaped] ?? escaped),
  );

// What in a file (its path and text) sets the verification mode, as a list of reasons. Every line
// counts, comments too: what reads as a comment in one language is text in another (a heredoc, a
// raw string, a YAML value), so the lines that name the mode on purpose are listed instead.
export function overrideProblems(path, source) {
  if (PROSE.test(path) || path === SELF) return [];
  const patterns = [...MODE, ...(WORKFLOW_PATHS.test(path) ? WORKFLOW : []), SHORT];
  const allowed = new Set(ALLOWED[path] ?? []);
  const lines = path.endsWith('.properties')
    ? properties(source)
    : source.split(/\r\n|\r|\n/).map((text, i) => ({ line: i + 1, text }));
  return lines.flatMap(({ line, text }) => {
    const trimmed = text.trim();
    if (allowed.has(trimmed)) return [];
    const hit = patterns.find((pattern) => pattern.test(text));
    return hit ? [`${path}:${line}: ${hit} in ${JSON.stringify(trimmed)}`] : [];
  });
}

test('no file Gradle or CI reads sets the verification mode', () => {
  const files = execFileSync('git', ['ls-files', '-z'], { cwd: ROOT }).toString().split('\0').filter(Boolean);
  assert.ok(files.length > 100, `git ls-files listed ${files.length} files`);
  assert.ok(files.includes(METADATA), `${METADATA} is not tracked`);
  const found = files.flatMap((path) => {
    const buffer = readFileSync(new URL(path, ROOT));
    // Binary files (images, fonts, the wrapper's jar) hold no setting.
    if (buffer.subarray(0, 8000).includes(0)) return [];
    return overrideProblems(path, buffer.toString('utf8'));
  });
  assert.ok(
    found.length === 0,
    `${found.join('\n')}\n\nA line that names this on purpose goes in \`ALLOWED\` in ${SELF}, ` +
      'as its trimmed text under its path.',
  );
});

test('each line allowed to name the mode is still there', () => {
  for (const [path, lines] of Object.entries(ALLOWED)) {
    const present = new Set(read(path).split('\n').map((l) => l.trim()));
    for (const allowed of lines) assert.ok(present.has(allowed), `${path} no longer has ${JSON.stringify(allowed)}`);
  }
});

// Overrides the check must refuse, each with its file, its text and what it is refused for.
const refusedOverrides = {
  'lenient in the root properties': ['gradle.properties', 'org.gradle.dependency.verification=lenient\n'],
  'off in a module properties file': ['app/gradle.properties', 'org.gradle.dependency.verification = off\n'],
  'a property split over two lines': ['gradle.properties', 'org.gradle.dependency.\\\n    verification=off\n'],
  'a property with a Unicode escape': ['gradle.properties', 'org.gradle.dependency\\u002everification=off\n'],
  'a property with an escaped dot': ['gradle.properties', 'org.gradle.dependency\\.verification=off\n'],
  'a property with escaped letters': ['gradle.properties', 'org.gradle.\\dependency.\\verification=off\n'],
  'a property after a comment ending in a backslash': [
    'gradle.properties',
    '# A comment, which Java never continues \\\norg.gradle.dependency.verification=off\n',
  ],
  'a property after a ! comment ending in a backslash': [
    'gradle.properties',
    '! A comment \\\n  org.gradle.dependency.verification=off\n',
  ],
  'a property after a value ending in an even run of backslashes': [
    'gradle.properties',
    'a=b\\\\\norg.gradle.dependency.verification=off\n',
  ],
  'a property in the JVM arguments': ['gradle.properties', 'org.gradle.jvmargs=-Xmx4g -Dorg.gradle.dependency\\u002everification=off\n'],
  'a property with a colon and blanks': ['gradle.properties', '  org.gradle.dependency.verification  :  off\n'],
  'a -D flag in a workflow': ['.github/workflows/ci.yml', '        run: ./gradlew build -Dorg.gradle.dependency.verification=off\n'],
  'GRADLE_OPTS in a workflow': ['.github/workflows/ci.yml', '      GRADLE_OPTS: -Dorg.gradle.dependency.verification=lenient\n'],
  'the long option in a workflow': ['.github/workflows/ci.yml', '        run: ./gradlew build --dependency-verification=off\n'],
  'the long option in an action': ['.github/actions/java-gradle/action.yml', '        arguments: --dependency-verification lenient\n'],
  'the short option': ['.github/workflows/ci.yml', '        run: ./gradlew build -F off\n'],
  'the short option, quoted and attached': ['.github/workflows/ci.yml', "        run: ./gradlew build '-Foff'\n"],
  'the short option on a continued line': [
    '.github/workflows/release.yml',
    '        run: |\n          ./gradlew :app:bundleRelease \\\n            -F lenient --console=plain\n',
  ],
  'the metadata written by a workflow': ['.github/workflows/ci.yml', '        run: ./gradlew build --write-verification-metadata sha256\n'],
  'the metadata written, short': ['.github/workflows/ci.yml', '        run: ./gradlew build -M sha256\n'],
  'the short option in a folded command': [
    '.github/workflows/ci.yml',
    '        run: >-\n          ./gradlew build\n          -F off\n',
  ],
  'the short option for writing in a folded command': [
    '.github/workflows/ci.yml',
    '        run: >\n          ./gradlew build\n          -M sha256\n',
  ],
  'the short option on its own line of a literal command': [
    '.github/workflows/ci.yml',
    '        run: |\n          ./gradlew build $(\n          echo -F off)\n',
  ],
  'the short option in a variable': ['.github/workflows/ci.yml', '        env:\n          ARGS: -F off\n        run: ./gradlew build $ARGS\n'],
  'the short option in a flow sequence': ['.github/actions/java-gradle/action.yml', '        arguments: [-F, lenient]\n'],
  'the short option in a script': ['tools/build.sh', 'args=(-F off)\n./gradlew build "${args[@]}"\n'],
  'the short option in a build script': ['build.gradle.kts', 'exec { commandLine("./gradlew", "-F", "off") }\n'],
  "Probe's variable in a workflow": ['.github/workflows/ci.yml', '      SLEEVESIGHT_WRITE_VERIFICATION_METADATA: sha256\n'],
  'the metadata removed by a workflow': ['.github/workflows/ci.yml', '        run: rm gradle/verification-metadata.xml\n'],
  'the refresh run by a workflow': ['.github/workflows/ci.yml', '        run: tools/refresh-dependency-verification.sh\n'],
  "the refresh's reset run by an action": [
    '.github/actions/java-gradle/action.yml',
    '      run: python3 -I tools/dependency-verification.py reset\n',
  ],
  'the refresh run by a script': ['.github/scripts/x.sh', 'bash tools/refresh-dependency-verification.sh\n'],
  'the mode in upper case, short': ['.github/workflows/ci.yml', '        run: ./gradlew build -F STRICT\n'],
  "the mode from a workflow's input": ['.github/workflows/ci.yml', '        run: ./gradlew build -F ${{ inputs.mode }}\n'],
  'the mode from a quoted variable': ['.github/workflows/ci.yml', '        run: ./gradlew build -F "$MODE"\n'],
  'the short option at the end of a continued line': ['.github/workflows/ci.yml', '        run: ./gradlew build -F \\\n          off\n'],
  'the metadata written in two kinds': ['tools/x.sh', './gradlew -M pgp,sha256 build\n'],
  'a comment line in a workflow': ['.github/workflows/ci.yml', '      # -Dorg.gradle.dependency.verification=off\n'],
  'code after a block comment closes': ['build.gradle.kts', '*/ System.setProperty("org.gradle.dependency.verification", "off")\n'],
  'code after a one-line block comment': ['settings.gradle.kts', '/* x */ gradle.startParameter.dependencyVerificationMode = OFF\n'],
  'a comment-looking line in a heredoc': ['tools/x.sh', 'cat > ~/.gradle/init.d/x.gradle <<EOF\n# x\n#startParameter.dependencyVerificationMode\nEOF\n'],
  'the mode set in a settings script': [
    'settings.gradle.kts',
    'gradle.startParameter.dependencyVerificationMode = DependencyVerificationMode.OFF\n',
  ],
  'verification off for every configuration in the root build': [
    'build.gradle.kts',
    'allprojects { configurations.configureEach { resolutionStrategy.disableDependencyVerification() } }\n',
  ],
  'verification off in a convention plugin': [
    'build-logic/src/main/kotlin/sleevesight.locking.gradle.kts',
    'configurations.all {\n    resolutionStrategy {\n        disableDependencyVerification()\n    }\n}\n',
  ],
  'verification off in a Groovy build script': ['app/build.gradle', 'configurations.all { resolutionStrategy.disableDependencyVerification() }\n'],
  "the start parameter's setter": [
    'settings.gradle.kts',
    'gradle.startParameter.setDependencyVerificationMode(org.gradle.api.artifacts.verification.DependencyVerificationMode.LENIENT)\n',
  ],
  "Gradle's internal override": [
    'build-logic/src/main/kotlin/x.gradle.kts',
    'val off = DependencyVerificationOverride.NO_VERIFICATION\n',
  ],
  'the metadata written from a build script': ['build.gradle.kts', 'gradle.startParameter.setWriteDependencyVerifications(listOf("sha256"))\n'],
  'the property in build logic': ['build-logic/build.gradle.kts', 'System.setProperty("org.gradle.dependency.verification", "off")\n'],
  'the property in the wrapper script': ['gradlew', 'DEFAULT_JVM_OPTS="-Dorg.gradle.dependency.verification=off"\n'],
  'an allowed line in another file': [
    'tools/other.sh',
    './gradlew --console=plain --write-verification-metadata sha256 --write-locks "${tasks[@]}"\n',
  ],
};

for (const [name, [path, text]] of Object.entries(refusedOverrides)) {
  test(`the override check refuses ${name}`, () => {
    assert.notDeepEqual(overrideProblems(path, text), [], name);
  });
}

test('the override check passes what the build has', () => {
  assert.deepEqual(overrideProblems('gradle.properties', 'org.gradle.caching=true\n'), []);
  assert.deepEqual(overrideProblems('design/development.md', 'org.gradle.dependency.verification=off\n'), []);
  assert.deepEqual(overrideProblems('gradle.properties', '# org.gradle.dependency.verification=off\n'), []);
  assert.deepEqual(overrideProblems('gradle.properties', '! org.gradle.dependency.verification=off\n'), []);
  assert.deepEqual(overrideProblems('gradle.properties', 'a=b\\\n  c\n'), []);
  assert.deepEqual(overrideProblems('.github/workflows/ci.yml', '        run: ./gradlew build -x test -Pa=b --no-F\n'), []);
  assert.deepEqual(overrideProblems('.github/workflows/ci.yml', '        run: ./gradlew build -x test -Pa=b\n'), []);
  // Other tools' `-F` and `-M`, and code.
  const passes = {
    'app/src/main/kotlin/x/A.kt': [
      'val lo = -Float.MAX_VALUE',
      'val angle = (-Math.PI / 2)',
      'offset = -MAX_SHIFT',
      'val name = "${a}-Foo"',
    ],
    '.github/workflows/release.yml': [
      'gh api repos/o/r/releases -F per_page=100',
      'gh api repos/o/r/releases -F offset=10 -F strictness=1',
      'curl -F "file=@a.aab" https://example.invalid',
      'grep -F -- "$x" file',
    ],
    '.githooks/merge-check': ['gdiff --raw -z -M --no-abbrev "$1" "$2" >"$work/raw"'],
    'fixtures/http/x.json': ['  "title": "Song -Mono Version",'],
    // Outside what CI runs, the verification files may be named.
    '.github/CODEOWNERS': ['/gradle/verification-metadata.xml @someone'],
    '.github/dependabot.yml': ['# (`tools/refresh-dependency-verification.sh`), and Dependabot closes its PR'],
  };
  for (const [path, lines] of Object.entries(passes)) {
    for (const line of lines) assert.deepEqual(overrideProblems(path, `${line}\n`), [], `${path}: ${line}`);
  }
});

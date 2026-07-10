# Vendored W3C RDF test suites

Source: https://github.com/w3c/rdf-tests
Commit: 61a15c221670532e9c309e916e1870629eadd30f
Fetched: 2026-07-10

Contents (copied unmodified, except for the deletions listed below):

- `rdf11/rdf-turtle/` — the RDF 1.1 Turtle test suite (`rdf/rdf11/rdf-turtle` in the source repo)
- `rdf11/rdf-trig/` — the RDF 1.1 TriG test suite (`rdf/rdf11/rdf-trig`)
- `rdf12/rdf-turtle/`, `rdf12/rdf-trig/` — the RDF 1.2 suites, used ONLY as robustness fuzz
  input (RDF-star / RDF 1.2 syntax is documented as unsupported by ttl-chunker)

Deleted to keep the tree small (not test data): `reports/` directories (EARL implementation
reports), `*.zip`, `*.gz`, `*.jsonld`, `index.html`, `template.haml`.

License: dual-licensed under the W3C Test Suite License and the W3C 3-clause BSD License —
see `LICENSE.md` (copied verbatim from the source repo) and the header comments in each
`manifest.ttl`.

Base IRI: each test file's relative IRIs resolve against its published IRI. The manifests
declare this via `mf:assumedTestBase` (e.g. `https://w3c.github.io/rdf-tests/rdf/rdf11/rdf-turtle/`);
the test harness (`W3cManifest`) reads it from the manifest.

How the suites are consumed:

- `W3cTurtleSuiteTest` / `W3cTrigSuiteTest` parse `rdf11/*/manifest.ttl` with RDF4J at test
  discovery time. Positive-syntax and eval entries are chunked and verified against RDF4J
  (every chunk parses standalone; the union of chunks is isomorphic to the original). Negative
  -syntax entries assert the robustness contract and a pinned PASSES/THROWS outcome from
  `expected-negative-outcomes.txt`.
- `W3cRdf12RobustnessTest` walks `rdf12/` action files and asserts only the robustness
  contract (terminates; only TurtleSyntaxException or normal completion).
- `exclusions.txt` lists tests to skip, one `name<TAB>reason` per line.

Re-fetching a newer snapshot (record the new SHA above and regenerate
`expected-negative-outcomes.txt` afterwards):

```bash
tmp=$(mktemp -d)
git clone --depth 1 --filter=blob:none --sparse https://github.com/w3c/rdf-tests.git "$tmp/rdf-tests"
git -C "$tmp/rdf-tests" sparse-checkout set rdf/rdf11/rdf-turtle rdf/rdf11/rdf-trig rdf/rdf12/rdf-turtle rdf/rdf12/rdf-trig
git -C "$tmp/rdf-tests" rev-parse HEAD
R=src/test/resources/w3c
rm -rf "$R/rdf11" "$R/rdf12"; mkdir -p "$R/rdf11" "$R/rdf12"
cp -R "$tmp/rdf-tests/rdf/rdf11/rdf-turtle" "$R/rdf11/"; cp -R "$tmp/rdf-tests/rdf/rdf11/rdf-trig" "$R/rdf11/"
cp -R "$tmp/rdf-tests/rdf/rdf12/rdf-turtle" "$R/rdf12/"; cp -R "$tmp/rdf-tests/rdf/rdf12/rdf-trig" "$R/rdf12/"
cp "$tmp/rdf-tests/LICENSE.md" "$R/"
rm -rf "$R"/rdf11/*/reports; find "$R" \( -name '*.zip' -o -name '*.gz' -o -name '*.jsonld' -o -name 'index.html' -o -name '*.haml' \) -delete
rm -rf "$tmp"
```

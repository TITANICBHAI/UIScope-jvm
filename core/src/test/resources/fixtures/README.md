# Test fixtures

## `android/` — raw `uiautomator dump` samples

**These are SYNTHETIC.** They were written by hand to match the structure of real `adb exec-out uiautomator dump /dev/stdout`
output (they were not captured from a device) so Phase 1 and the parser work can start before real captures exist.

| File | What it is | Used to test |
|---|---|---|
| `g1-clean.xml` | The G1 sample from the plan: 2 nodes, XML only, no trailing text | parser happy path (2 nodes) |
| `g1-trailing-suffix.xml` | Same XML followed by `UI hierchary dumped to: /dev/stdout` (byte-identical to the plan's G1 line) | bug B1 (suffix must not make the tree empty) |
| `g1-crlf.xml` | Same 2 nodes, pretty-printed, every line ending is CR LF, suffix included | CRLF handling |
| `error-prefixed.txt` | An `ERROR:` line, then the valid XML and the suffix | text outside the XML span is ignored/mapped |
| `error-null-root.txt` | Only `ERROR: null root node returned by UiTestAutomationBridge.` (no tree) | typed error `DUMP_NULL_ROOT`, never an empty tree |
| `list-duplicate-ids.xml` | A RecyclerView of 4 rows; `row_title`/`row_subtitle`/`row_icon`/`order_row` repeat 4×, one node has no id, non-ASCII and `&amp;` in text. 22 nodes | duplicate-id uniqueness, selectors, diff |
| `dialog-over-activity.xml` | Two window roots: an app window and a permission dialog (later = on top) that overlaps the app's map view. Contains `Don't allow` (apostrophe) | hit-test G11, hostile-string escaping |

The list and dialog samples use the API 30+ attribute set (`drawing-order`, `hint`, `display-id`); the G1 samples use the older set. A
parser must accept both.

Do not "tidy" these files: they are byte-exact on purpose (`.gitattributes` marks `fixtures/**` as `-text`; CRLF and trailing text matter).

## `harness/`

`sample.bin` — 6 bytes (`A`, CR, LF, UTF-8 BOM) used to test that the fixture loader returns bytes untouched.

## Real captures (Round 0)

Run `scripts/capture-fixtures.sh` (macOS/Linux) or `scripts/capture-fixtures.ps1` (Windows) with a phone attached, then hand the
output folder over. Real captures go to `android/real/<device>-<screen>.dump.txt` (+ `.png`), with a README line giving
**device, Android version and date**. Nothing is committed under `android/real/` yet.

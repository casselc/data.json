# Experimental whole-String reader

This source-only Jolt candidate reduces mutable-reader and interop crossings
while parsing maps, vectors and punctuation. It is opt-in; normal data.json,
JVM/Babashka use and `read` of Reader input remain unchanged.

```clojure
(require '[clojure.data.json :as json]
         '[clojure.data.json.jolt-native :as native])
(binding [json/*experimental-native-reader* (native/load-reader!)]
  (json/read-str source :bigdec true))
```

The native parser constructs ordinary Jolt persistent collections. Every
number uses the stock data.json conversion, including BigInt/BigDecimal and
floating-point options. Escaped strings reuse the existing source-qualified
String token decoder. It never rewrites persisted data or installs itself
globally. An unavailable native loader fails visibly; ordinary default parsing
does not need it.

Incomplete inputs, duplicate keys, depth above 64 and unsupported malformed
tokens decline to the original reader. Duplicate-key behavior therefore remains
the original last-value behavior. Key/value callbacks bypass the native backend.
Extra-data callbacks preserve stock unread suffixes and effects by falling back
when any source characters remain, including trailing whitespace. Unsupported
input does not narrow the portable reader's accepted domain.

The backend receives immutable source, merged options and a stock number
conversion callback. It returns `[value end]` or declines with nil/false. Shape
checks do not prove its semantics: the loaded Scheme and runtime collection
helpers are a trusted implementation boundary. Each invocation owns its cursor;
no input, cursor, mutable parser or result scratch is shared between calls.

This is a performance candidate, not complete JSON conformance or an application
pin. Error/callback/Unicode/numeric parity tests and bounded native tests are
required, as are broader consumer qualification and independent review.
Historical permissive raw-control handling is not claimed fixed here; consumers
requiring strict JSON must retain their external lexical/integrity gates.
Standalone/AOT loading is not qualified. A compiler-bearing Jolt CLI is required.

Run `jolt -M:whole-reader-test`, then the established reader, writer and portable
gates. Native workloads must be serialized in this workspace. Performance must
use real Durable publication and independent normal-decoder recovery; green
parser tests alone do not establish throughput or persistence.

(ns clojure.data.json.jolt-native
  "Explicit, source-only qualification loader for data.json's guarded backend."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [jolt.scheme :as scheme]))

;; Embed the exact library resource at macro expansion. This makes the source
;; an input of the namespace compilation rather than an arbitrary runtime path.
;; AOT retention/resource invalidation still require their own qualification;
;; eval-string needs the compiler-bearing Jolt CLI and is not a native loader.
(defmacro ^:private writer-source []
  (let [resource "clojure/data/json/jolt_native.ss"]
    (if-let [url (io/resource resource)]
      (slurp url)
      (throw (ex-info "Missing data.json native writer resource"
                      {:resource resource})))))

(def ^:private source (writer-source))

(defmacro ^:private reader-source []
  (let [resource "clojure/data/json/jolt_string_reader.ss"]
    (if-let [url (io/resource resource)]
      (slurp url)
      (throw (ex-info "Missing data.json native reader resource"
                      {:resource resource})))))

(def ^:private string-reader-source (reader-source))

(defmacro ^:private whole-reader-source []
  (let [resource "clojure/data/json/jolt_whole_reader.ss"]
    (if-let [url (io/resource resource)]
      (slurp url)
      (throw (ex-info "Missing data.json whole reader resource"
                      {:resource resource})))))

(def ^:private whole-source (whole-reader-source))

(defonce ^:private state
  (atom {:backend nil :loaded? false :enabled-by-default? false
         :required-helper "pmap-fold-seq-order"
         :resource "clojure/data/json/jolt_native.ss"
         :aot-qualified? false}))

(defonce ^:private writer
  (delay
    ;; Require the runtime-owned traversal. Never substitute a library copy of
    ;; the HAMT representation or the differently ordered pmap-fold-fwd.
    (scheme/proc "pmap-fold-seq-order")
    (let [loaded (scheme/eval-string source)]
      (swap! state assoc :backend :guarded-chez :loaded? true
             :guard :uncached-per-value :sink :string-writer)
      loaded)))

(defonce ^:private string-reader-state
  (atom {:backend nil :loaded? false :enabled-by-default? false
         :resource "clojure/data/json/jolt_string_reader.ss"
         :aot-qualified? false}))

(defonce ^:private string-reader
  (delay
    (let [loaded (scheme/eval-string string-reader-source)]
      (swap! string-reader-state assoc :backend :guarded-chez :loaded? true
             :guard :scalar-escapes-with-portable-decline)
      loaded)))

(defn backend-info
  "Report successful initialization, not whether a caller has opted in.
  Missing helpers or failed source loading never report a loaded backend."
  []
  @state)

(defn load-writer!
  "Return the experimental writer for explicit binding to
  clojure.data.json/*experimental-native-writer*. Requires the runtime-owned
  pmap-fold-seq-order helper and the compiler-bearing Jolt CLI. Does not install
  anything globally, replace JSONWriter methods, or enable itself by default."
  []
  @writer)

(defn load-payload-writer!
  "Return a fresh experimental key-caching writer for one caller-owned serial
  payload. Bind it to clojure.data.json/*experimental-native-writer* only for
  that operation, then discard it. Nested synchronous writes are supported;
  concurrent callers must each obtain their own writer. Do not convey this
  writer binding into parallel serialization workers.

  Only escaped object keys are cached, bounded to 128 entries and 8,192
  retained key-plus-encoded characters. Values, callbacks and dispatch methods
  are not cached. There is no process-global payload cache or native resource
  to close; retaining the returned closure retains its bounded key cache.
  This uses the existing qualified source-mode loader and guards. It does not
  install a backend globally, qualify standalone/AOT, or change load-writer!."
  []
  ;; Initialize definitions once; never retain payload closures in loader state.
  (load-writer!)
  ((scheme/proc "djn-make-key-cache-writer")))

(defn load-payload-string-caching-writer!
  "Return a source-only experimental writer for one caller-owned serial payload.
  In addition to the existing key cache, this explicitly retains stock-encoded
  string fragments: at most 128 entries and 65,536 key-plus-output characters,
  with input strings limited to 256 characters. Discard after the payload.

  Every value still resolves its current JSONWriter before consulting the cache;
  custom writers, callbacks and dispatch methods are not cached. Escape flags
  qualify each hit. Independent/concurrent payloads must use separate closures.
  Unlike load-payload-writer!, this opt-in retains telemetry string values until
  the caller discards the writer. No global/default or AOT qualification."
  []
  (load-writer!)
  ((scheme/proc "djn-make-string-cache-writer")))

(defmacro ^:private byte-writer-source []
  (str (slurp (io/resource "clojure/data/json/jolt_byte_batch.ss")) "\n"
       (slurp (io/resource "clojure/data/json/jolt_byte_writer.ss"))))

(def ^:private byte-source (byte-writer-source))
(def ^:private byte-factory
  (delay (do (load-writer!) (scheme/eval-string byte-source))))

(defn load-payload-byte-buffer-writer!
  "Experimental default-option byte sink. Flushes to the caller's real
  StringWriter before observable protocol classification, custom writers, and
  exceptional departure. Unsupported options retain the existing guarded path.
  Owns at most one reusable 64KiB byte scratch plus decimal scratch when idle;
  nested writes obtain separate loans. One serial payload only; never share
  the returned writer between parallel workers. Discard it after the payload.
  Requires the observable-boundary runtime helper; not standalone qualified."
  []
  (@byte-factory))

(defn write-terminated-row!
  "Internal opt-in row adapter for a bound byte-buffer writer. Emits the row
  newline before final materialization; nested JSON writes retain normal
  four-argument behavior. Not a general JSON option or persistence operation."
  [row out]
  (json/*experimental-native-writer* row out json/default-write-options
                                    @#'clojure.data.json/native-writer-stock "\n"))

(defn write-batch-text!
  "Experimental source-only, serial JSONEachRow collector. Owns one byte buffer
  for this call and returns immutable text once. Warm stock rows avoid row
  strings; cold classification and custom writers still receive their real row-local
  StringWriter prefix. Nested ordinary JSON writes keep their existing meaning.
  Check UTF-8 budget after each complete row, before requesting the next row.
  A trusted single-row writer may allocate before its size is known. No SQL,
  WAL, admission, persistence or default backend selection is performed here."
  [rows max-bytes]
  (when-not (and (integer? max-bytes) (not (neg? max-bytes)))
    (throw (ex-info "Invalid JSONEachRow byte limit" {:type ::invalid-limit})))
  (when-not (or (nil? rows) (sequential? rows))
    (throw (ex-info "JSONEachRow requires sequential rows" {:type ::invalid-rows})))
  (let [write (load-payload-byte-buffer-writer!)
        make (scheme/proc "djn-make-byte-batch")
        write-rows (scheme/proc "djn-byte-batch-write-rows!")
        batch (make)
        stock @#'clojure.data.json/native-writer-stock
        overflow (fn [] (throw (ex-info "JSONEachRow output exceeds its byte limit"
                                       {:type ::output-limit})))]
    (binding [json/*experimental-native-writer* write]
      (write-rows rows write stock batch max-bytes overflow))))

(defn load-string-reader!
  "Return the source-only experimental String token reader for explicit binding
  to clojure.data.json/*experimental-native-string-reader*. Does not install a
  global parser or change the writer. Simple and valid Unicode escapes are native;
  malformed/noncanonical escapes and EOF decline to the established reader.
  Requires the compiler-bearing Jolt CLI; standalone/AOT is not qualified."
  []
  @string-reader)

(defn string-reader-info
  "Report reader initialization separately from unchanged writer backend-info.
  Successful loading does not imply an active caller binding."
  []
  @string-reader-state)

(defonce ^:private whole-reader
  (delay (scheme/eval-string whole-source)))

(defn load-reader!
  "Return a source-only experimental whole-String reader for explicit binding
  to clojure.data.json/*experimental-native-reader*. Scalar strings and numbers
  retain existing decoders. Unsupported inputs/options decline to stock.
  No global installation, Reader interception or standalone/AOT qualification."
  []
  (let [parse @whole-reader
        token-reader (load-string-reader!)]
    (fn [source _options decode-number]
      (parse source token-reader decode-number))))

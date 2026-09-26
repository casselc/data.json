(ns clojure.data.json.jolt-native
  "Explicit, source-only qualification loader for data.json's guarded backend."
  (:require [clojure.data.json]
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

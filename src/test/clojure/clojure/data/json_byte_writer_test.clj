(ns clojure.data.json-byte-writer-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.data.json :as json]
            [clojure.data.json.jolt-native :as native]
            [jolt.scheme :as scheme]))

;; Source-only integration spike; does NOT claim the bcb executable contains
;; this hook. Load the exact edited runtime function into this isolated process.
(let [source (slurp "/home/chuck/ai-src/worktrees/jolt-protocol-observable-boundary-20261007/host/chez/protocols.ss")]
  (scheme/eval-string
    (subs source (.indexOf source "(define (make-protocol-method-site")
                 (.indexOf source ";; Fixed-arity entry points"))))

(defn candidate [x]
  (binding [json/*experimental-native-writer* (native/load-payload-byte-buffer-writer!)]
    (json/write-str x)))

(deftest stock-wire-and-option-delegation
  (doseq [x [nil true false "é😀/\n" 0 -1 Long/MIN_VALUE Long/MAX_VALUE
             18446744073709551615N 999999999999999999999999999999999999N
             [1 "x" []] {"a" "é" "b" [true nil]} :ns/named 1.25
             (apply str (repeat 70000 "é😀/"))]]
    (is (= (json/write-str x) (candidate x))))
  (doseq [options [{:escape-unicode false} {:escape-slash false}
                   {:key-fn (fn [k] (str "custom-" (name k)))}]]
    (let [x {:a "é/"} expected (json/write-str x options)]
      (is (= expected
             (binding [json/*experimental-native-writer* (native/load-payload-byte-buffer-writer!)]
               (json/write-str x options)))))))

(def seen (atom []))
(deftype PrefixValue []
  json/JSONWriter
  (-write [_ out options]
    (swap! seen conj (.toString out))
    (json/write "custom" out options)))

(deftest custom-writer-sees-real-prefix-once
  (reset! seen [])
  (is (= "[\"prefix\",\"custom\",\"suffix\"]"
         (candidate ["prefix" (PrefixValue.) "suffix"])))
  (is (= ["[\"prefix\","] @seen)))

(deftest error-preserves-partial-output
  (let [out (java.io.StringWriter.)]
    (binding [json/*experimental-native-writer* (native/load-payload-byte-buffer-writer!)]
      (is (thrown? Throwable (json/write ["prefix" Double/NaN] out))))
    (is (= "[\"prefix\"," (.toString out)))))

(def predicate-probe
  (scheme/eval-string
    "(lambda (out run)
       (let ((arms jt-user-value-tags-arms) (domains jt-user-value-tags-domain-snapshot)
             (classes jolt-class-arms) (tags value-host-tags) (observations '()))
         (dynamic-wind
           (lambda ()
             ((var-deref \"clojure.core\" \"__register-class!\")
               (lambda (x) (when (boolean? x)
                             (set! observations (cons (sb-str out) observations))) #f)
               (lambda (x) \"byte.writer.Predicate\") (lambda (x) empty-pvec)))
           (lambda () (jolt-invoke0 run) (apply jolt-vector (reverse observations)))
           (lambda () (set! jt-user-value-tags-arms arms)
                      (set! jt-user-value-tags-domain-snapshot domains)
                      (set! jolt-class-arms classes) (set! value-host-tags tags)))))"))

(deftest receiver-predicate-sees-real-prefix-once
  (let [out (java.io.StringWriter.)
        write (native/load-payload-byte-buffer-writer!)
        rows ["prefix" true "suffix"]
        observed (predicate-probe
                   out #(write rows out json/default-write-options @#'json/native-writer-stock))
        original (java.io.StringWriter.)
        old-write (native/load-writer!)
        baseline (predicate-probe
                   original #(old-write rows original json/default-write-options @#'json/native-writer-stock))]
    (is (= baseline observed))
    (is (= "[\"prefix\"," (last observed)))
    (is (= "[\"prefix\",true,\"suffix\"]" (.toString out)))))

(deftype NestedValue []
  json/JSONWriter
  (-write [_ out _options]
    (.append out (json/write-str ["nested" true]))))

(deftest terminated-row-keeps-nested-json-unterminated
  (let [write (native/load-payload-byte-buffer-writer!)
        out (java.io.StringWriter.)]
    (binding [json/*experimental-native-writer* write]
      (native/write-terminated-row! ["prefix" (NestedValue.) "suffix"] out)
      (is (= "[\"prefix\",[\"nested\",true],\"suffix\"]\n" (.toString out)))
      (is (= "[\"later\",7]" (json/write-str ["later" 7])))))
  (let [out (java.io.StringWriter.)]
    (binding [json/*experimental-native-writer* (native/load-payload-byte-buffer-writer!)]
      (is (thrown? Throwable (native/write-terminated-row! ["prefix" Double/NaN] out))))
    (is (= "[\"prefix\"," (.toString out)))))

(native/load-writer!)
(def empty-fast-probe
  (scheme/eval-string
    "(lambda (run)
       (let ((original djn-empty-default-vector!) (calls 0))
         (dynamic-wind
           (lambda ()
             (set! djn-empty-default-vector!
               (lambda (value out options stock)
                 (when (and (pvec? value) (= (pvec-count value) 0))
                   (set! calls (+ calls 1)))
                 (original value out options stock))))
           (lambda () (jolt-invoke0 run) calls)
           (lambda () (set! djn-empty-default-vector! original)))))"))

(deftest earlier-empty-vector-fast-path-is-preserved
  (let [write (native/load-payload-byte-buffer-writer!)
        out (java.io.StringWriter.)
        calls (empty-fast-probe
                #(write [] out json/default-write-options @#'json/native-writer-stock "\n"))]
    (is (= 1 calls))
    (is (= "[]\n" (.toString out)))))

(deftype CatchNestedError []
  json/JSONWriter
  (-write [_ out _options]
    (try (json/write-str ["inner" Double/NaN])
         (catch Throwable _ nil))
    (.append out (json/write-str ["recovered" 7]))))

(deftest nested-error-restores-outer-context-and-idle-writer
  (let [write (native/load-payload-byte-buffer-writer!)
        out (java.io.StringWriter.)]
    (binding [json/*experimental-native-writer* write]
      (native/write-terminated-row! ["before" (CatchNestedError.) "after"] out)
      (is (= "[\"before\",[\"recovered\",7],\"after\"]\n" (.toString out)))
      (is (= "[\"fresh\",true]" (json/write-str ["fresh" true]))))))

(deftest qualification-cache-does-not-hide-changed-default-flags
  (let [write (native/load-payload-byte-buffer-writer!)
        stock @#'json/native-writer-stock
        options json/default-write-options
        changed (assoc options :escape-unicode false :escape-slash false)]
    (doseq [[opts captured expected]
            [[options stock "[\"\\u00e9\\/\"]"]
             [changed (assoc stock 9 changed)
              "[\"é/\"]"]
             [options stock "[\"\\u00e9\\/\"]"]]]
      (let [out (java.io.StringWriter.)]
        ;; Reach flag qualification rather than the earlier identity decline.
        (is (identical? opts (nth captured 9)))
        (write ["é/"] out opts captured)
        (is (= expected (.toString out)))))))

(deftest ascii-and-escaping-buffer-boundaries
  (doseq [n [0 1 255 256 65534 65535 65536 65537]]
    (doseq [tail ["" "/" "\"" "\\" "é😀\n"]]
      (let [value [(str (apply str (repeat n "a")) tail)]]
        (is (= (json/write-str value) (candidate value)))))))

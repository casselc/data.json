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

(deftest byte-writer-capability-slots-restore-across-reentry-and-later-rows
  ;; Exercise every cached stock slot after a nested writer returns/throws.
  ;; A factory-wide snapshot or an unrestored inner row must not leak into
  ;; the remainder of the outer row or the next row on this same factory.
  (let [write (native/load-payload-byte-buffer-writer!)
        values [nil true 42 18446744073709551615N 1.25 :named "é/" {"a" 7} [8]]
        out (java.io.StringWriter.)]
    (binding [json/*experimental-native-writer* write]
      (native/write-terminated-row! (into [(NestedValue.) (CatchNestedError.)] values) out)
      (is (= (str "[[\"nested\",true],[\"recovered\",7],"
                  (subs (json/write-str values) 1) "\n")
             (.toString out)))
      (let [next-out (java.io.StringWriter.)]
        (native/write-terminated-row! values next-out)
        (is (= (str (json/write-str values) "\n") (.toString next-out)))))))

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

(deftest batch-stock-wire-and-buffer-growth
  (doseq [rows [[] [nil true false 1 -1 1.25 "é😀/"]
                [[1 "x"] {"a" "é" "b" [nil true]} []]
                [[(apply str (repeat 70000 "x"))] ["last"]]]]
    (let [expected (apply str (map #(str (json/write-str %) "\n") rows))]
      (is (= expected (native/write-batch-text! rows 1000000))))))

(def batch-materialization-probe
  (scheme/eval-string
    "(lambda (run)
       (let ((original djn-byte-batch-text) (calls 0))
         (dynamic-wind
           (lambda ()
             (set! djn-byte-batch-text
               (lambda (batch start)
                 (set! calls (+ calls 1)) (original batch start))))
           (lambda () (jolt-invoke0 run) calls)
           (lambda () (set! djn-byte-batch-text original)))))"))

(deftest warm-stock-rows-do-not-materialize-per-row
  ;; Cold method-family resolution must publish its view before classification.
  ;; The current core hook only omits proven callback-free warm family hits.
  (native/load-payload-byte-buffer-writer!)
  (let [rows (vec (repeat 20 ["constant"]))
        output (atom nil)
        calls (batch-materialization-probe #(reset! output (native/write-batch-text! rows 1000)))]
    (is (= (apply str (repeat 20 "[\"constant\"]\n")) @output))
    ;; Vector + String cold boundaries, completed escaped first row, final batch.
    (is (= 4 calls))))

(deftest batch-receiver-predicate-observes-row-local-prefix
  (let [write (native/load-payload-byte-buffer-writer!)
        make (scheme/proc "djn-make-byte-batch")
        text (scheme/proc "djn-byte-batch-text")
        batch (make) out (java.io.StringWriter.)
        stock @#'json/native-writer-stock
        original (java.io.StringWriter.)
        old-write (native/load-writer!)
        row ["prefix" true "suffix"]]
    (write [0] (java.io.StringWriter.) json/default-write-options stock "\n" batch)
    (let [observed (predicate-probe out #(write row out json/default-write-options stock "\n" batch))
          expected (predicate-probe original #(old-write row original json/default-write-options stock))]
      (is (= expected observed))
      (is (= "[0]\n[\"prefix\",true,\"suffix\"]\n" (text batch 0)))
      (is (= "[\"prefix\",true,\"suffix\"]\n" (.toString out))))))

(deftest batch-custom-writer-observes-only-current-row
  (reset! seen [])
  (is (= "[0]\n[\"prefix\",\"custom\",\"suffix\"]\n[2]\n"
         (native/write-batch-text! [[0] ["prefix" (PrefixValue.) "suffix"] [2]] 1000)))
  (is (= ["[\"prefix\","] @seen)))

(def retained-rows (atom []))
(def retained-error (ex-info "synthetic retained-writer error" {:type ::retained-error}))
(deftype RetainWriter [fail?]
  json/JSONWriter
  (-write [_ out options]
    (swap! retained-rows conj out)
    (json/write "retained" out options)
    (when fail? (throw retained-error))))

(deftest batch-retained-writers-have-completed-local-row-or-error-prefix
  (reset! retained-rows [])
  (let [row ["before" (RetainWriter. false) "after"]]
    (is (= "[0]\n[\"before\",\"retained\",\"after\"]\n[2]\n"
           (native/write-batch-text! [[0] row [2]] 1000)))
    (is (= "[\"before\",\"retained\",\"after\"]\n"
           (.toString (first @retained-rows)))))
  (reset! retained-rows [])
  (is (identical? retained-error
                  (try (native/write-batch-text! [[0] ["before" (RetainWriter. true) "after"]] 1000)
                       nil (catch Throwable e e))))
  (is (= "[\"before\",\"retained\"" (.toString (first @retained-rows)))))

(deftype ReplacePrefix []
  json/JSONWriter
  (-write [_ out options]
    ;; The current Jolt model lacks StringWriter.getBuffer. Exercise the actual
    ;; native writer mutation via its runtime-owned setter, not a fake sink.
    ((scheme/proc "sb-set!") out "[")
    (json/write "replacement" out options)))

(deftest batch-reconciles-custom-prefix-replacement-and-nested-error
  (doseq [value [(ReplacePrefix.) (CatchNestedError.) (NestedValue.)]]
    (let [rows [[0] ["before" value "after"] [2]]
          expected (apply str (map #(str (candidate %) "\n") rows))]
      (is (= expected (native/write-batch-text! rows 1000))))))

(deftest batch-budget-stops-before-next-unchunked-row
  (let [visits (atom [])
        rows ((fn walk [values]
                (lazy-seq (when (seq values)
                            (swap! visits conj (first values))
                            (cons (first values) (walk (rest values))))))
              [[1] [2] [3]])]
    (is (= ::native/output-limit
           (:type (ex-data (try (native/write-batch-text! rows 3)
                               nil (catch Throwable e e))))))
    (is (= [[1]] @visits)))
  (is (= "[1]\n" (native/write-batch-text! [[1]] 4)))
  (is (= "" (native/write-batch-text! [] 0))))

(deftest batch-empty-fast-path-budget-is-byte-exact
  (is (= "[]\n" (native/write-batch-text! [[]] 3)))
  (is (= ::native/output-limit
         (:type (ex-data (try (native/write-batch-text! [[]] 2)
                             nil (catch Throwable error error))))))
  (let [visits (atom [])
        rows ((fn walk [values]
                (lazy-seq (when (seq values)
                            (swap! visits conj (first values))
                            (cons (first values) (walk (rest values))))))
              [[] [42] [3]])]
    (is (= ::native/output-limit
           (:type (ex-data (try (native/write-batch-text! rows 3)
                               nil (catch Throwable error error))))))
    (is (= [[] [42]] @visits))))

(deftype LargeReplacement [text]
  json/JSONWriter
  (-write [_ out _]
    ((scheme/proc "sb-set!") out (str "[" (json/write-str text) ",false"))))

(deftest batch-replacement-growth-preserves-adjacent-rows-and-budget
  (let [text (apply str (repeat 70000 "a"))
        rows [[0] ["old-prefix" (LargeReplacement. text)] [2]]
        expected (str "[0]\n[" (json/write-str text) ",false]\n[2]\n")
        size (alength (.getBytes expected "UTF-8"))]
    (is (= expected (native/write-batch-text! rows size)))
    (is (= ::native/output-limit
           (:type (ex-data (try (native/write-batch-text! rows (dec size))
                               nil (catch Throwable error error))))))))

(deftest batch-shrinking-replacement-updates-budget
  (let [rows [[(apply str (repeat 100 "a")) (ReplacePrefix.)]]
        expected "[\"replacement\"]\n"
        size (count expected)]
    (is (= expected (native/write-batch-text! rows size)))
    (is (= ::native/output-limit
           (:type (ex-data (try (native/write-batch-text! rows (dec size))
                               nil (catch Throwable error error))))))))

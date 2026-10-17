(ns clojure.data.json-native-string-output-test
  (:require [clojure.data.json :as json]
            [clojure.data.json.jolt-native :as native]
            [jolt.scheme :as scheme]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]))

;; This focused namespace runs in a fresh process. Initialize the optional
;; factory before any test ordering can turn "preinstalled" into "later".
(def ^:private preinstalled-output
  (with-redefs [json/write-str (fn [_] "before-first-load")]
    (native/write-str [])))

(deftest string-output-keeps-exact-default-wire-and-independent-results
  (doseq [value [[] (with-meta [] {:source :test}) [1] '() {} nil
                 ["quote\"slash\\漢字😀" 1 true nil] {"a" [1 2]}]]
    (is (= (json/write-str value) (native/write-str value))))
  (let [first (native/write-str [])]
    (is (= "[1]" (native/write-str [1])))
    (is (= "[]" first))))

(deftest preinstalled-and-later-public-writer-replacements-remain-live
  (is (= "before-first-load" preinstalled-output))
  (with-redefs [json/write-str (fn [_] "before-init")]
    (is (= "before-init" (native/write-str []))))
  (is (= "[]" (native/write-str [])))
  (with-redefs [json/write-str (fn [_] "later")]
    (is (= "later" (native/write-str [])))))

(deftest changed-options-dispatcher-and-native-hook-use-the-current-public-path
  (with-redefs [json/default-write-options
                (assoc json/default-write-options :indent true)]
    (is (= (json/write-str []) (native/write-str []))))
  (with-redefs [json/-write (fn [_ out _] (.write out "dispatcher"))]
    (is (= "dispatcher" (native/write-str []))))
  (let [calls (atom 0)]
    (binding [json/*experimental-native-writer*
              (fn [_ out _ _] (swap! calls inc) (.write out "native-hook"))]
      (is (= "native-hook" (native/write-str [])))
      (is (= 1 @calls)))))

(deftest custom-vector-writer-gets-one-fresh-real-sink-and-no-replayed-call
  (let [original @#'json/write-array calls (atom 0) sinks (atom [])]
    (try
      (extend java.util.Collection json/JSONWriter
              {:-write (fn [_ out _]
                         (swap! calls inc)
                         (is (= "" (.toString out)))
                         (swap! sinks conj out)
                         (.append (.getBuffer out) "[7]"))})
      (is (= "[7]" (native/write-str [])))
      (is (= 1 @calls))
      (is (= "[7]" (native/write-str [])))
      (is (= 2 @calls))
      (is (not (identical? (first @sinks) (second @sinks))))
      (finally (extend java.util.Collection json/JSONWriter {:-write original})))))

(deftest selected-custom-writer-error-is-not-retried
  (let [original @#'json/write-array calls (atom 0)
        failure (ex-info "synthetic writer error" {}) caught (atom nil)]
    (try
      (extend java.util.Collection json/JSONWriter
              {:-write (fn [_ _ _] (swap! calls inc) (throw failure))})
      (try (native/write-str []) (catch Throwable e (reset! caught e)))
      (is (identical? failure @caught))
      (is (= 1 @calls))
      (finally (extend java.util.Collection json/JSONWriter {:-write original})))))

(deftest lazy-byte-row-production-uses-its-owned-companion-not-standalone-fallback
  (let [factory @#'native/string-output-factory
        [observed count-calls]
        ((scheme/eval-string
           "(lambda (factory)
              (let ((calls 0))
                (jolt-vector
                  (lambda (original wv hv ov stock hook render)
                    (factory original wv hv ov stock hook
                      (lambda (value options stock)
                        (set! calls (+ calls 1)) (render value options stock))))
                  (lambda () calls))))") @factory)
        rows (map (fn [_]
                    (is (some? json/*experimental-native-writer*))
                    {"events" (native/write-str []) "links" (native/write-str [])})
                  (range 3))]
    (with-redefs [native/string-output-factory (delay observed)]
      (is (= (apply str (repeat 3 "{\"events\":\"[]\",\"links\":\"[]\"}\n"))
             (native/write-prefixed-batch-text! "" rows 1000))))
    ;; A standalone adapter under the byte binding produces identical wire
    ;; but zero companion calls. This witness rejects that previous mismatch.
    (is (= 6 (count-calls)))))

(deftest changed-hooks-inside-lazy-production-still-decline-the-owned-companion
  (let [calls (atom 0)
        rows (map (fn [_]
                    (let [nested
                          (binding [json/*experimental-native-writer*
                                    (fn [_ out _ _]
                                      (swap! calls inc) (.write out "override"))]
                            (native/write-str []))]
                      {"nested" nested})) (range 2))]
    (is (= "{\"nested\":\"override\"}\n{\"nested\":\"override\"}\n"
           (native/write-batch-text! rows 1000)))
    (is (= 2 @calls))
    (is (= "[]" (native/write-str [])))))

(deftest companion-preserves-active-prefix-reentry-and-retained-custom-sink
  (let [original @#'json/write-array outer (atom nil) inner (atom nil)
        calls (atom 0)
        callback (reify json/JSONWriter
                   (-write [_ out _]
                     (reset! outer out)
                     (.write out (native/write-str []))))]
    (try
      (extend java.util.Collection json/JSONWriter
              {:-write (fn [value out options]
                         (if (empty? value)
                           (do
                             (swap! calls inc) (reset! inner out)
                             (is (= "" (.toString out)))
                             (is (= "{\"nested\":" (.toString @outer)))
                             (is (not (identical? out @outer)))
                             (.append @outer " ")
                             ;; A separate factory/binding must restore the
                             ;; enclosing companion and active row afterwards.
                             (is (= "{\"n\":1}\n"
                                    (native/write-batch-text! [{"n" 1}] 1000)))
                             (.write out "[7]"))
                           (original value out options)))})
      (let [wire (native/write-batch-text! [{"nested" callback}] 1000)]
        (is (= "{\"nested\": [7]}\n" wire))
        (is (= 1 @calls))
        (is (= "{\"nested\": [7]}\n" (.toString @outer)))
        (is (= "[7]" (.toString @inner)))
        (.append @inner "later")
        (is (= "{\"nested\": [7]}\n" wire)))
      (finally (extend java.util.Collection json/JSONWriter {:-write original})))))

(deftest stock-empty-result-does-not-end-the-enclosing-writer-borrow
  (let [outer (atom nil)
        callback (reify json/JSONWriter
                   (-write [_ out _]
                     (reset! outer out)
                     (.write out (native/write-str []))
                     (.append out " ")))]
    (is (= "{\"nested\":[] }\n"
           (native/write-batch-text! [{"nested" callback}] 1000)))
    (is (= "{\"nested\":[] }\n" (.toString @outer)))))

(deftest dropping-borrow-restoration-demonstrably-loses-later-callback-output
  (native/load-payload-byte-buffer-writer!)
  (let [source (slurp (io/resource "clojure/data/json/jolt_byte_writer.ss"))
        changed (str/replace source "(when borrowed? (set! exposed? #t))"
                                    "(when #f (set! exposed? #t))")
        _ (is (not= source changed))
        ;; Keep the mutant factory and all helpers lexical; do not replace
        ;; production runtime definitions or the library's initialized factory.
        factory (scheme/eval-string (str "(let () " changed ")"))
        callback (reify json/JSONWriter
                   (-write [_ out _]
                     (.write out (native/write-str []))
                     (.append out " ")))
        expected "{\"nested\":[] }\n"
        bad (with-redefs [native/load-payload-byte-buffer-writer! (fn [] (factory))]
              (native/write-batch-text! [{"nested" callback}] 1000))]
    (is (string? bad))
    (is (not= expected bad))
    (is (= expected (native/write-batch-text! [{"nested" callback}] 1000)))))

(deftest companion-custom-error-keeps-prefix-and-does-not-poison-next-batch
  (let [original @#'json/write-array outer (atom nil) calls (atom 0)
        failure (ex-info "synthetic companion error" {}) caught (atom nil)
        callback (reify json/JSONWriter
                   (-write [_ out _]
                     (reset! outer out)
                     (.write out (native/write-str []))))]
    (try
      (extend java.util.Collection json/JSONWriter
              {:-write (fn [value out options]
                         (if (empty? value)
                           (do (swap! calls inc) (.append @outer " ") (throw failure))
                           (original value out options)))})
      (try (native/write-batch-text! [{"nested" callback}] 1000)
           (catch Throwable e (reset! caught e)))
      (is (identical? failure @caught))
      (is (= 1 @calls))
      (is (= "{\"nested\": " (.toString @outer)))
      (finally (extend java.util.Collection json/JSONWriter {:-write original})))
    (is (= "{\"ok\":\"[]\"}\n"
           (native/write-batch-text! (map (fn [_] {"ok" (native/write-str [])}) [0]) 1000)))))

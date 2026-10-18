(ns clojure.data.json-stock-sorted-fold-test
  "Strict optional capability gate; run on a runtime providing stock map folds."
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.data.json :as json] [clojure.data.json.jolt-native :as native]
            [clojure.java.io :as io] [clojure.string :as string]
            [jolt.scheme :as scheme]))

(native/load-payload-byte-buffer-writer!)
(when-not (scheme/eval-string "(and djn-stock-sorted-map-folder #t)")
  (throw (ex-info "Strict stock-fold gate requires both optional runtime helpers" {})))

(defn candidate [x]
  (binding [json/*experimental-native-writer* (native/load-payload-byte-buffer-writer!)]
    (json/write-str x)))

(deftest stock-fold-selection-and-same-output-disabled-control
  (let [source (slurp (io/resource "clojure/data/json/jolt_byte_writer.ss"))
        observed (string/replace source "(define (emit-stock-sorted-map! x)"
                                 "(define (emit-stock-sorted-map! x) (set! visits (+ visits 1))")
        mutant (string/replace observed "                   (emit-stock-sorted-map! x)"
                               "                   (emit-seq-map! x)")
        probe (fn [body]
                (let [factory (scheme/eval-string
                                (str "(let ((visits 0)) " body
                                     " (jolt-vector djn-make-byte-buffer-writer (lambda () visits)))"))
                      out (java.io.StringWriter.)]
                  (((nth factory 0)) (sorted-map "a" 42 "b" false) out json/default-write-options
                                     @#'json/native-writer-stock)
                  [(.toString out) ((nth factory 1))]))]
    (is (not= source observed))
    (is (not= observed mutant))
    (is (= ["{\"a\":42,\"b\":false}" 1] (probe observed)))
    (is (= ["{\"a\":42,\"b\":false}" 0] (probe mutant)))))

(deftest optional-helper-absence-keeps-same-bytes
  (let [source (slurp (io/resource "clojure/data/json/jolt_byte_writer.ss"))
        factory (scheme/eval-string
                  (str "(let ((top-level-bound? (lambda (name) #f))) " source
                       " djn-make-byte-buffer-writer)"))
        out (java.io.StringWriter.) value (sorted-map "a" 42 "b" [false nil])]
    ((factory) value out json/default-write-options @#'json/native-writer-stock)
    (is (= (json/write-str value) (.toString out)))))

(deftest wrong-arity-and-noncallable-helpers-decline-without-trial-calls
  (doseq [[admit fold]
          [["(lambda () (set! calls (+ calls 1)) #f)"
            "(lambda (m visit acc) (set! calls (+ calls 1)) acc)"]
           ["(lambda (m) (set! calls (+ calls 1)) #f)"
            "(lambda () (set! calls (+ calls 1)) #f)"]
           ["7" "(lambda (m visit acc) (set! calls (+ calls 1)) acc)"]
           ["(lambda (m) (set! calls (+ calls 1)) #f)" "7"]]]
    (let [source (slurp (io/resource "clojure/data/json/jolt_byte_writer.ss"))
          probe (scheme/eval-string
                  (str "(let ((calls 0))
                          (let ((top-level-bound? (lambda (name) #t))
                                (top-level-value (lambda (name)
                                  (case name
                                    ((stock-sorted-map?) " admit ")
                                    ((sorted-map-fold-seq-order) " fold ")
                                    (else (error 'test \"unexpected helper\")))))) "
                       source
                       " (jolt-vector djn-make-byte-buffer-writer
                           (lambda () djn-stock-sorted-map-folder)
                           (lambda () calls))))"))
          out (java.io.StringWriter.) value (sorted-map "a" 42 "b" false)]
      (((nth probe 0)) value out json/default-write-options @#'json/native-writer-stock)
      (is (false? ((nth probe 1))))
      (is (zero? ((nth probe 2))))
      (is (= (json/write-str value) (.toString out))))))

(deftest stock-fold-handles-nonscalar-values-with-live-child-writers
  (let [seen (atom [])
        child (reify json/JSONWriter
                (-write [_ out _]
                  (swap! seen conj (.toString out))
                  (.write out (candidate (sorted-map "inner" false)))))
        value (sorted-map "a" child "b" [nil 1.5])]
    (is (= "{\"a\":{\"inner\":false},\"b\":[null,1.5]}" (candidate value)))
    (is (= ["{\"a\":"] @seen))))

(deftest live-scalar-writer-prefix-edits-and-replacement
  (doseq [mode [:append :clear :reenter :throw :replace-writer]]
    (let [run (fn [native?]
                (let [effects (atom []) calls (atom 0) out (java.io.StringWriter.)
                      value (sorted-map "a" "first" "b" "second")
                      original (nth @#'json/native-writer-stock 6)
                      replacement (fn [v writer options]
                                    (swap! effects conj [:replacement v (.toString writer)])
                                    (original v writer options))]
                  (try
                    (extend java.lang.String json/JSONWriter
                      {:-write (fn [v writer options]
                                 (swap! effects conj [:writer v (.toString writer)])
                                 (when (= 1 (swap! calls inc))
                                   (case mode
                                     :append (.write writer " ")
                                     :clear (do (.setLength (.getBuffer writer) 0) (.write writer "edited "))
                                     :reenter (.write writer (candidate (sorted-map "inner" false)))
                                     :throw (throw (ex-info "synthetic scalar failure" {}))
                                     :replace-writer (extend java.lang.String json/JSONWriter {:-write replacement})))
                                 (original v writer options))})
                    [(try (if native?
                            ((native/load-payload-byte-buffer-writer!) value out json/default-write-options
                                                                       @#'json/native-writer-stock)
                            (json/write value out))
                          :ok (catch Throwable error (.getMessage error)))
                     (.toString out) @effects]
                    (finally (extend java.lang.String json/JSONWriter {:-write original})))))]
      (is (= (run false) (run true)) (str "scalar callback: " mode)))))

(defn -main [& _]
  (let [r (run-tests 'clojure.data.json-stock-sorted-fold-test)]
    (System/exit (if (and (pos? (:test r)) (zero? (+ (:fail r) (:error r)))) 0 1))))

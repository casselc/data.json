(ns clojure.data.json-default-omission-test
  (:require [jolt.scheme :as scheme]))

;; Core = is lowered to a native operation, so with-redefs of its public Var
;; is NOT an observer. Install this isolated-process probe before JSON loads.
(scheme/eval-string
  "(define djo-marker #f)
   (define djo-calls 0)
   (let ((original jolt=2))
     (set! jolt=2 (lambda (a b)
                    (when (and djo-marker (eq? a djo-marker))
                      (set! djo-calls (+ djo-calls 1)))
                    (original a b))))
   (define (djo-observe marker) (set! djo-marker marker) (set! djo-calls 0))
   (define (djo-count) djo-calls)")
(require '[clojure.test :refer [deftest is run-tests]]
         '[clojure.data.json :as json]
         '[clojure.data.json.jolt-native :as native]
         '[clojure.java.io :as io])

(defn marker-checks [marker f]
  (let [observe (scheme/proc "djo-observe") count-checks (scheme/proc "djo-count")]
    (observe marker)
    ;; Independent non-vacuity control before each actual operation.
    ((scheme/proc "jolt=2") marker 7)
    (assert (= 1 (count-checks)))
    (observe marker)
    (try [(f) (count-checks)] (finally (observe false)))))

(deftest default-marker-does-not-request-structural-equality
  (let [sentinel (:value-fn json/default-write-options)
        value {"nested" (sorted-map "b" false "a" 42)}
        [result calls] (marker-checks sentinel #(json/write-str value))]
    (is (= "{\"nested\":{\"a\":42,\"b\":false}}" result))
    (is (zero? calls))))

(deftest custom-value-function-keeps-its-equality-and-transformations
  (let [transform (fn marker [k v] (if (= "skip" k) marker v))
        value (array-map "keep" (sorted-map "b" false "a" 42) "skip" 9)
        [result calls] (marker-checks transform #(json/write-str value :value-fn transform))]
    (is (= "{\"keep\":{\"a\":42,\"b\":false}}" result))
    ;; Outer keep/skip and the nested two values retain the custom check.
    ;; The runtime may also recurse after normalizing the sorted value.
    (is (<= 4 calls))))

(deftest native-default-marker-check-is-identity-not-a-normalizing-equality
  (native/load-payload-byte-buffer-writer!)
  (let [source (slurp (io/resource "clojure/data/json/jolt_byte_writer.ss"))
        factory (scheme/eval-string
                  (str "(let ((calls 0) (original jolt=2)
                              (marker (pmap-fast-get
                                (var-deref \"clojure.data.json\" \"default-write-options\")
                                djn-value-fn pmap-absent)))
                          (let ((jolt=2 (lambda (a b)
                                          (when (eq? a marker) (set! calls (+ calls 1)))
                                          (original a b)))) " source
                       " (jolt-vector djn-make-byte-buffer-writer (lambda () calls))))"))
        out (java.io.StringWriter.)
        value {"nested" (sorted-map "b" false "a" 42)}]
    (((nth factory 0)) value out json/default-write-options @#'json/native-writer-stock)
    (is (= "{\"nested\":{\"a\":42,\"b\":false}}" (.toString out)))
    (is (zero? ((nth factory 1))))))

(deftest literal-default-marker-still-omits-and-all-writers-agree
  (let [marker (:value-fn json/default-write-options)]
    (doseq [value [(array-map "skip" marker "keep" (sorted-map "a" 42))
                   (sorted-map "skip" marker "keep" {"a" 42})]]
      (is (= "{\"keep\":{\"a\":42}}" (json/write-str value)))
      (doseq [writer [(native/load-writer!) (native/load-payload-writer!)
                      (native/load-payload-byte-buffer-writer!)]]
        (is (= "{\"keep\":{\"a\":42}}"
               (binding [json/*experimental-native-writer* writer] (json/write-str value)))))
      (is (= "{\"keep\":{\"a\":42}}\n" (native/write-batch-text! [value] 10000))))))

(defn -main [& _]
  (let [r (run-tests 'clojure.data.json-default-omission-test)]
    (assert (= 4 (:test r)))
    (prn {:gate :default-omission-identity :complete true
          :passed (zero? (+ (:fail r) (:error r)))})
    (System/exit (if (zero? (+ (:fail r) (:error r))) 0 1))))

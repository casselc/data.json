(ns clojure.data.json-sorted-byte-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.data.json :as json]
            [clojure.data.json.jolt-native :as native]
            [jolt.host :as host]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [jolt.scheme :as scheme]))

(defn candidate [value]
  (binding [json/*experimental-native-writer* (native/load-payload-byte-buffer-writer!)]
    (json/write-str value)))

(deftest sorted-values-and-order
  (doseq [value [(sorted-map) (sorted-map "b" false "a" "é\n😀" "c" nil)
                 (sorted-map-by #(compare %2 %1) "b" [42] "a" true)
                 [(sorted-map "nested" (sorted-map "x" 42))]]]
    (is (= (json/write-str value) (candidate value)))
    (is (= (str (json/write-str value) "\n")
           (native/write-batch-text! [value] 10000)))))

(deftest traversal-does-not-invoke-comparator
  (let [armed (atom false) calls (atom 0)
        value (sorted-map-by (fn [a b]
                               (when @armed
                                 (swap! calls inc)
                                 (throw (ex-info "unexpected comparison" {})))
                               (compare a b)) "b" false "a" 42)]
    (reset! armed true)
    (is (= "{\"a\":42,\"b\":false}" (candidate value)))
    (is (zero? @calls))))

(deftest custom-table-seq-sees-published-prefix-and-can-replace-it
  (let [value (sorted-map "original" 42)
        ops (host/ref-get value :ops)
        writer (java.io.StringWriter.) seen (atom [])]
    ;; Use the supported table operation surface, not a forged native tree.
    (host/ref-put! value :ops
                   (assoc ops :seq
                          (fn [_]
                            (swap! seen conj (.toString writer))
                            (.write writer " ")
                            (seq [["replacement" false]]))))
    ((native/load-payload-byte-buffer-writer!) value writer json/default-write-options
                                               @#'json/native-writer-stock)
    (is (= ["{"] @seen))
    (is (= "{ \"replacement\":false}" (.toString writer)))))

(deftest child-callback-reentry-and-exception-recovery
  (let [seen (atom nil)
        child (reify json/JSONWriter
                (-write [_ out _]
                  (reset! seen (.toString out))
                  (.write out (candidate (sorted-map "inner" true)))))
        value (sorted-map "a" child "z" 42)]
    (is (= "{\"a\":{\"inner\":true},\"z\":42}" (candidate value)))
    (is (= "{\"a\":" @seen)))
  (let [write (native/load-payload-byte-buffer-writer!)
        bad (sorted-map "x" (reify json/JSONWriter
                              (-write [_ _ _] (throw (ex-info "expected" {})))))]
    (is (thrown? Throwable
          (write bad (java.io.StringWriter.) json/default-write-options @#'json/native-writer-stock)))
    (let [out (java.io.StringWriter.)]
      (write (sorted-map "ok" true) out json/default-write-options @#'json/native-writer-stock)
      (is (= "{\"ok\":true}" (.toString out))))))

(deftest custom-sequence-next-and-child-effects-match-stock
  (let [run (fn [write]
              (let [out (java.io.StringWriter.) effects (atom [])
                    child (reify json/JSONWriter
                            (-write [_ writer _]
                              (swap! effects conj [:child (.toString writer)])
                              (.write writer "42")))
                    value (sorted-map "ignored" false)
                    ops (host/ref-get value :ops)]
                (host/ref-put! value :ops
                  (assoc ops :seq
                    (fn [_]
                      (swap! effects conj [:seq (.toString out)])
                      (cons ["a" child]
                            (lazy-seq
                              (swap! effects conj [:next (.toString out)])
                              (list ["z" false]))))))
                (write value out)
                [(.toString out) @effects]))
        stock (run #(json/write %1 %2))
        native (run #((native/load-payload-byte-buffer-writer!)
                      %1 %2 json/default-write-options @#'json/native-writer-stock))]
    (is (= stock native))
    (is (= [[:seq "{"] [:next "{"] [:child "{\"a\":"]] (second native)))))

(deftest live-map-method-and-dispatcher-are-not-bypassed
  (let [value (sorted-map "a" 42) seen (atom [])
        original (nth @#'json/native-writer-stock 7)]
    (try
      (extend java.util.Map json/JSONWriter
              {:-write (fn [receiver out _]
                         (swap! seen conj (identical? value receiver))
                         (.write out "\"custom-map\""))})
      (is (= "\"custom-map\"" (candidate value)))
      (is (= [true] @seen))
      (finally (extend java.util.Map json/JSONWriter {:-write original})))
    (with-redefs [json/-write (fn [_ out _] (.write out "\"custom-root\""))]
      (is (= "\"custom-root\"" (candidate value))))))

(deftest native-map-sequence-branch-has-a-same-output-negative-control
  (native/load-payload-byte-buffer-writer!)
  (let [source (slurp (io/resource "clojure/data/json/jolt_byte_writer.ss"))
        observed (string/replace source "(define (emit-seq-map! x)"
                                  "(define (emit-seq-map! x) (set! visits (+ visits 1))")
        mutant (string/replace observed "               (emit-seq-map! x))"
                                "               (invoke! impl x))")
        probe (fn [body]
                (let [factory (scheme/eval-string
                                (str "(let ((visits 0)) " body
                                     " (jolt-vector djn-make-byte-buffer-writer (lambda () visits)))"))
                      out (java.io.StringWriter.)]
                  (((nth factory 0)) (sorted-map "a" 42) out json/default-write-options
                                      @#'json/native-writer-stock)
                  [(.toString out) ((nth factory 1))]))]
    (is (not= source observed))
    (is (not= observed mutant))
    (is (= ["{\"a\":42}" 1] (probe observed)))
    (is (= ["{\"a\":42}" 0] (probe mutant)))))

(defn -main [& _]
  (let [r (run-tests 'clojure.data.json-sorted-byte-test)]
    (System/exit (if (zero? (+ (:fail r) (:error r))) 0 1))))

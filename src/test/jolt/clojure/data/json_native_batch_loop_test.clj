(ns clojure.data.json-native-batch-loop-test
  "Real compiled runtime gate; no runtime source injection."
  (:require [clojure.test :refer [deftest is]]
            [clojure.data.json :as json]
            [clojure.data.json.jolt-native :as native]
            [jolt.scheme :as scheme]))

(defn- stock [rows]
  (apply str (map #(str (json/write-str %) "\n") rows)))

(deftest wire-parity-for-demand-shapes
  (let [rows [[nil false true 1 -1 1.25 "é😀/\n"]
              {"nested" [18446744073709551615N]} []
              [(apply str (repeat 70000 "a"))]]
        expected (stock rows)]
    (doseq [input [rows (apply list rows) (map identity rows) (subvec rows 0)]]
      (is (= expected (native/write-batch-text! input Long/MAX_VALUE))))
    (is (= "" (native/write-batch-text! nil 0)))))

(deftest retained-row-writers-are-distinct-and-complete
  (let [writers (atom []) prefixes (atom [])
        row (reify json/JSONWriter
              (-write [_ out options]
                (swap! prefixes conj (.toString out))
                (swap! writers conj out)
                (json/write "value" out options)))
        rows [[0 row] [1 row]]]
    (is (= "[0,\"value\"]\n[1,\"value\"]\n"
           (native/write-batch-text! rows 100)))
    (is (= ["[0," "[1,"] @prefixes))
    (is (not (identical? (first @writers) (second @writers))))
    (is (= ["[0,\"value\"]\n" "[1,\"value\"]\n"]
           (mapv #(.toString %) @writers)))))

(deftest overflow-does-not-demand-next-row
  (let [visits (atom [])
        rows ((fn walk [values]
                (lazy-seq
                  (when (seq values)
                    (swap! visits conj (first values))
                    (cons (first values) (walk (rest values))))))
              [[1] [2] [3]])]
    (is (= ::native/output-limit
           (:type (ex-data (try (native/write-batch-text! rows 3)
                               nil (catch Throwable error error))))))
    (is (= [[1]] @visits)))
  (is (= "[1]\n" (native/write-batch-text! [[1]] 4))))

(deftest exceptional-and-nested-callback-bindings-settle
  (let [original-error (ex-info "expected" {:expected true})
        bad (reify json/JSONWriter (-write [_ _ _] (throw original-error)))
        nested (reify json/JSONWriter
                 (-write [_ out _]
                   (.append out (.trim (native/write-batch-text! [[true]] 100)))))]
    (is (identical? original-error
                    (try (native/write-batch-text! [[bad]] 100)
                         nil (catch Throwable error error))))
    (is (nil? json/*experimental-native-writer*))
    (is (= "[[true]]\n" (native/write-batch-text! [[nested]] 100)))
    (is (nil? json/*experimental-native-writer*))
    (is (= "false\n" (native/write-batch-text! [false] 6)))))

(deftest each-row-sees-live-default-options
  (let [original json/default-write-options
        change (reify json/JSONWriter
                 (-write [_ out _]
                   (alter-var-root #'json/default-write-options
                                   #(assoc % :escape-unicode false :escape-slash false))
                   (.append out "true")))]
    (try
      (is (= "true\n\"é/\"\n"
             (native/write-batch-text! [change "é/"] 100)))
      (finally (alter-var-root #'json/default-write-options (constantly original))))))

(deftest integer-scratch-copy-spans-output-buffer-boundaries
  ;; Place the sign/digits across every relevant boundary, not merely in an
  ;; otherwise empty row. Wide unsigned values and fallback bignums stay exact.
  (doseq [length [65510 65512 65515 65516 65517 65518 65519 65520 65530 65535 65536]
          value [0 -1 Long/MIN_VALUE Long/MAX_VALUE 18446744073709551615N
                 999999999999999999999999999999999999N]]
    (let [rows [[(apply str (repeat length "a")) value] [42]]
          expected (stock rows)]
      (is (= expected (native/write-batch-text! rows Long/MAX_VALUE))))))

(deftest scheme-loop-preserves-overflow-callback-exception-identity
  (let [write (native/load-payload-byte-buffer-writer!)
        loop-rows (scheme/proc "djn-byte-batch-write-rows!")
        batch ((scheme/proc "djn-make-byte-batch"))
        error (ex-info "expected overflow sentinel" {:type ::native/output-limit})]
    (binding [json/*experimental-native-writer* write]
      (is (identical? error
                      (try (loop-rows [true] write @#'json/native-writer-stock batch 0
                                      (fn [] (throw error)))
                           nil (catch Throwable observed observed)))))
    (is (nil? json/*experimental-native-writer*))))

(deftest terminated-row-adapter-keeps-five-argument-contract
  (binding [json/*experimental-native-writer* (native/load-payload-byte-buffer-writer!)]
    (let [out (java.io.StringWriter.)]
      (native/write-terminated-row! ["é/" Long/MIN_VALUE] out)
      (is (= (str (json/write-str ["é/" Long/MIN_VALUE]) "\n") (.toString out)))))
  (is (nil? json/*experimental-native-writer*)))

(defn -main [& _]
  (let [r (clojure.test/run-tests 'clojure.data.json-native-batch-loop-test)]
    (System/exit (if (zero? (+ (:fail r) (:error r))) 0 1))))

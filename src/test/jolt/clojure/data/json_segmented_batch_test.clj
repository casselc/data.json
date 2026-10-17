(ns clojure.data.json-segmented-batch-test
  "Actual compiled-runtime gate; no injected runtime function definitions."
  (:require [clojure.test :refer [deftest is]]
            [clojure.data.json :as json]
            [clojure.data.json.jolt-native :as native]
            [jolt.scheme :as scheme]))

(defn- stock [rows]
  (apply str (map #(str (json/write-str %) "\n") rows)))

(deftest warmed-unexposed-rows-reuse-a-sink-without-sharing-exposed-sinks
  (native/load-payload-byte-buffer-writer!)
  (let [factory @@#'native/bound-row-writer-factory
        sinks (atom [])
        rows (vec (repeat 32 ["warm" 42 {"key" "value"}]))]
    ;; Internal allocation spy, not an application-visible writer callback.
    (with-redefs [native/bound-row-writer-factory
                  (delay (fn [write methods batch]
                           (let [emit (factory write methods batch)]
                             (fn [row out options]
                               (swap! sinks conj out)
                               (emit row out options)))))]
      (is (= (stock rows) (native/write-batch-text! rows 100000))))
    (is (= 32 (count @sinks)))
    ;; A cold site must publish its prefix before classification; only later
    ;; rows can prove their sink unexposed. Do not require cold-row reuse.
    (is (not (identical? (first @sinks) (second @sinks))))
    (is (every? #(identical? (last @sinks) %) (take-last 8 @sinks)))))

(deftest proof-registry-returns-the-registered-getter-not-a-lock-body-thunk
  (native/load-payload-byte-buffer-writer!)
  (let [check (scheme/eval-string
                "(lambda ()
                   (let ((write (lambda () #f)) (proof (lambda () #t)))
                     (djn-register-byte-writer-sink-proof! write proof)
                     (and (eq? proof (djn-byte-writer-sink-proof write))
                          (not (djn-byte-writer-sink-proof (lambda () #f))))))")]
    (is (true? (check)))))

(defn- retained-sink-control [force-reuse?]
  (native/load-payload-byte-buffer-writer!)
  (let [factory @@#'native/bound-row-writer-factory
        sinks (atom [])
        marker (fn [label]
                 (reify json/JSONWriter
                   (-write [_ out options]
                     (swap! sinks conj out)
                     (json/write label out options))))
        rows (into (vec (repeat 4 ["warm" 42]))
                   [[(marker "first")] ["warm" 42] [(marker "second")] ["warm" 42]])
        run #(native/write-batch-text! rows 100000)
        text (if force-reuse?
               (with-redefs [native/bound-row-writer-factory
                             (delay (fn [write methods batch]
                                      (let [emit (factory write methods batch)]
                                        (fn [row out options]
                                          (emit row out options)
                                          ;; Checked known-bad control: ignore
                                          ;; actual exposure, never native bounds.
                                          true))))]
                 (run))
               (run))]
    {:text text :same-sink? (identical? (first @sinks) (second @sinks))
     :retained (mapv #(.toString %) @sinks)}))

(deftest exposure-proof-protects-retained-writers-and-kills-always-reuse-mutant
  (let [normal (retained-sink-control false) mutant (retained-sink-control true)]
    (is (= (:text normal) (:text mutant)))
    (is (= ["[\"first\"]\n" "[\"second\"]\n"] (:retained normal)))
    (is (false? (:same-sink? normal)))
    (is (true? (:same-sink? mutant)))
    (is (not= (:retained normal) (:retained mutant)))))

(deftest unknown-procedure-factories-cannot-authorize-sink-reuse
  (native/load-payload-byte-buffer-writer!)
  (let [original native/load-payload-byte-buffer-writer! sinks (atom [])
        write (original)
        replacement (fn [& args]
                      (when (seq args) (swap! sinks conj (second args)))
                      (apply write args))
        rows (vec (repeat 8 ["warm" 42]))]
    (with-redefs [native/load-payload-byte-buffer-writer! (fn [] replacement)]
      (is (= (stock rows) (native/write-batch-text! rows 10000))))
    (is (= 8 (count @sinks)))
    (is (every? false? (map identical? @sinks (rest @sinks))))))

(deftest sink-proof-registry-does-not-retain-completed-factories
  (let [weak (scheme/eval-string "(lambda (value) (weak-cons value #f))")
        dead? (scheme/eval-string "(lambda (pair) (bwp-object? (car pair)))")
        make-weak (fn [] (weak (native/load-payload-byte-buffer-writer!)))
        references (mapv (fn [_] (make-weak)) (range 8))]
    (dotimes [_ 2] (scheme/eval-string "(collect (collect-maximum-generation))"))
    (is (every? true? (map dead? references)))))

(deftest exact-segment-boundaries-and-unicode
  (doseq [n [0 1 65530 65531 65532 65535 65536 131067 131068]]
    (let [rows [[(apply str (repeat n "a"))] ["é😀/\n" false] []]
          expected (stock rows) size (alength (.getBytes expected "UTF-8"))]
      (is (= expected (native/write-batch-text! rows size)))
      (is (= ::native/output-limit
             (:type (ex-data (try (native/write-batch-text! rows (dec size))
                                 nil (catch Throwable error error)))))))))

(deftest shrinking-large-row-preserves-prefix-and-retained-writer
  (doseq [n [65530 65531 65532 131067 131068]
          middle [1 65536 200000]]
    (let [retained (atom nil)
          replace (reify json/JSONWriter
                    (-write [_ out options]
                      (reset! retained out)
                      ((scheme/proc "sb-set!") out "[")
                      (json/write "replacement" out options)))
          rows [[(apply str (repeat n "a"))]
                [(apply str (repeat middle "b")) replace "after"] [2]]
          expected (stock rows)
          size (alength (.getBytes expected "UTF-8"))]
      (is (= expected (native/write-batch-text! rows size)))
      (is (= "[\"replacement\",\"after\"]\n" (.toString @retained)))
      (is (= ::native/output-limit
             (:type (ex-data (try (native/write-batch-text! rows (dec size))
                                 nil (catch Throwable error error)))))))))

(deftest completed-text-does-not-alias-later-batches
  (let [rows [[(apply str (repeat 131071 "x"))]]
        a (native/write-batch-text! rows 200000)
        b (native/write-batch-text! [["different"]] 100)]
    (is (= (stock rows) a))
    (is (= "[\"different\"]\n" b))))

(deftest lazy-overflow-and-original-error-remain-ordered
  (let [visits (atom [])
        rows ((fn walk [values]
                (lazy-seq (when (seq values)
                            (swap! visits conj (first values))
                            (cons (first values) (walk (rest values)))))) [1 2 3])]
    (is (= ::native/output-limit
           (:type (ex-data (try (native/write-batch-text! rows 1)
                               nil (catch Throwable error error))))))
    (is (= [1] @visits)))
  (let [error (ex-info "original" {:expected true})
        bad (reify json/JSONWriter (-write [_ _ _] (throw error)))]
    (is (identical? error (try (native/write-batch-text! [bad] 0)
                              nil (catch Throwable observed observed))))
    (is (nil? json/*experimental-native-writer*))))

(deftest fixed-prefix-survives-custom-row-replacement
  (doseq [n [0 65535 65536 131071]]
    (let [prefix (str (apply str (repeat n "p")) "β😀\n")
          seen (atom []) retained (atom nil)
          replacement (reify json/JSONWriter
                        (-write [_ out _]
                          (swap! seen conj (.toString out))
                          (reset! retained out)
                          ((scheme/proc "sb-set!") out "[42")))
          rows [[0] [1 replacement 2]]
          payload "[0]\n[42,2]\n"
          size (alength (.getBytes payload "UTF-8"))]
      (is (= (str prefix payload) (native/write-prefixed-batch-text! prefix rows size)))
      (is (= ["[1,"] @seen))
      (is (= "[42,2]\n" (.toString @retained)))
      (is (= prefix (native/write-prefixed-batch-text! prefix [] 0)))
      (is (= ::native/output-limit
             (:type (ex-data (try (native/write-prefixed-batch-text! prefix rows (dec size))
                                 nil (catch Throwable error error)))))))))

(deftest owned-byte-output-parity-boundaries-and-independent-arrays
  (doseq [n [0 1 65530 65535 65536 131071]]
    (let [prefix "INSERT INTO t FORMAT JSONCompactEachRow\n"
          rows [[(apply str (repeat n "a"))] ["é😀/\n" false] []]
          payload (stock rows) budget (alength (.getBytes payload "UTF-8"))
          output (native/write-prefixed-batch-bytes! prefix rows budget)]
      (is (bytes? output))
      (is (java.util.Arrays/equals (.getBytes (str prefix payload) "UTF-8") output))
      (aset-byte output 0 (byte 88))
      (is (java.util.Arrays/equals (.getBytes (str prefix payload) "UTF-8")
                                  (native/write-prefixed-batch-bytes! prefix rows budget)))
      (is (= ::native/output-limit
             (:type (ex-data (try (native/write-prefixed-batch-bytes! prefix rows (dec budget))
                                 nil (catch Throwable error error))))))))
  (is (= "" (String. (native/write-prefixed-batch-bytes! "" [] 0) "UTF-8")))
  (is (= "β😀" (String. (native/write-prefixed-batch-bytes! "β😀" [] 0) "UTF-8"))))

(deftest owned-bytes-retain-row-local-custom-writer-and-single-pass-failure-effects
  (let [seen (atom []) retained (atom nil)
        replacement (reify json/JSONWriter
                      (-write [_ out _]
                        (swap! seen conj (.toString out))
                        (reset! retained out)
                        ((scheme/proc "sb-set!") out "[42")))
        output (native/write-prefixed-batch-bytes! "prefix" [[0] [1 replacement 2]] 100)]
    (is (= "prefix[0]\n[42,2]\n" (String. output "UTF-8")))
    (is (= ["[1,"] @seen))
    (is (= "[42,2]\n" (.toString @retained)))
    ((scheme/proc "sb-set!") @retained "changed")
    (is (= "prefix[0]\n[42,2]\n" (String. output "UTF-8"))))
  (let [visits (atom [])
        rows ((fn walk [values]
                (lazy-seq (when (seq values)
                            (swap! visits conj (first values))
                            (cons (first values) (walk (rest values)))))) [1 2 3])]
    (is (= ::native/output-limit
           (:type (ex-data (try (native/write-prefixed-batch-bytes! "prefix" rows 1)
                               nil (catch Throwable error error))))))
    (is (= [1] @visits)))
  (let [error (ex-info "original" {:expected true})
        bad (reify json/JSONWriter (-write [_ _ _] (throw error)))]
    (is (identical? error (try (native/write-prefixed-batch-bytes! "prefix" [bad] 0)
                              nil (catch Throwable observed observed))))
    (is (nil? json/*experimental-native-writer*))))

(deftest reused-byte-factory-restores-nested-and-failed-row-contexts
  (let [write (native/load-payload-byte-buffer-writer!)
        capability @#'json/native-writer-stock
        options json/default-write-options
        batch ((scheme/proc "djn-make-byte-batch"))
        retained (atom nil)
        error (ex-info "nested original" {:expected true})
        bad (reify json/JSONWriter (-write [_ _ _] (throw error)))
        nested (reify json/JSONWriter
                 (-write [_ out _]
                   (reset! retained out)
                   (is (= "[1," (.toString out)))
                   (is (identical? error
                                   (try (json/write [7 bad] (java.io.StringWriter.))
                                        nil (catch Throwable seen seen))))
                   (is (= "[1," (.toString out)))
                   (let [inner (java.io.StringWriter.)]
                     (json/write ["inner" 7] inner)
                     (is (= "[\"inner\",7]" (.toString inner)))
                     (.append out (.toString inner)))))]
    (binding [json/*experimental-native-writer* write]
      (write [1 nested 3] (java.io.StringWriter.) options capability "\n" batch)
      (is (= "[1,[\"inner\",7],3]\n" (.toString @retained)))
      (write [4] (java.io.StringWriter.) options capability "\n" batch)
      (is (= "[1,[\"inner\",7],3]\n[4]\n"
             ((scheme/proc "djn-byte-batch-text") batch 0)))
      (is (identical? error (try (json/write [1 bad] (java.io.StringWriter.))
                                 nil (catch Throwable seen seen))))
      (let [after (java.io.StringWriter.)]
        (json/write [9 "after"] after)
        (is (= "[9,\"after\"]" (.toString after)))))))

(deftest three-level-byte-factory-restores-large-buffer-and-integer-scratch
  (let [write (native/load-payload-byte-buffer-writer!)
        capability @#'json/native-writer-stock
        options json/default-write-options
        batch ((scheme/proc "djn-make-byte-batch"))
        outer-text (apply str (repeat 65536 "o"))
        child-text (apply str (repeat 65536 "c"))
        grand-text (apply str (repeat 65536 "g"))
        low -9223372036854775808
        high 18446744073709551615N
        grand-value ["grand" low high grand-text]
        child-value ["child" child-text grand-value high]
        outer-value [low outer-text child-value 42]
        expected-grand (json/write-str grand-value)
        expected-child (json/write-str child-value)
        expected-outer (str (json/write-str outer-value) "\n")
        grand-writer (atom nil)
        child-writer (atom nil)
        outer-writer (java.io.StringWriter.)
        leaf (reify json/JSONWriter
               (-write [_ out _]
                 (let [inner (java.io.StringWriter.)]
                   (reset! grand-writer inner)
                   (json/write grand-value inner)
                   (.append out (.toString inner)))))
        middle (reify json/JSONWriter
                 (-write [_ out _]
                   (let [inner (java.io.StringWriter.)]
                     (reset! child-writer inner)
                     (json/write ["child" child-text leaf high] inner)
                     (.append out (.toString inner)))))]
    (binding [json/*experimental-native-writer* write]
      (write [low outer-text middle 42] outer-writer options capability "\n" batch)
      (write [9] (java.io.StringWriter.) options capability "\n" batch))
    (is (= expected-grand (.toString @grand-writer)))
    (is (= expected-child (.toString @child-writer)))
    (is (= expected-outer (.toString outer-writer)))
    (is (= (str expected-outer "[9]\n")
           ((scheme/proc "djn-byte-batch-text") batch 0)))))

(defn -main [& _]
  (let [r (clojure.test/run-tests 'clojure.data.json-segmented-batch-test)]
    (System/exit (if (zero? (+ (:fail r) (:error r))) 0 1))))

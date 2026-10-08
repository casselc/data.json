(ns clojure.data.json-segmented-batch-test
  "Actual compiled-runtime gate; no injected runtime function definitions."
  (:require [clojure.test :refer [deftest is]]
            [clojure.data.json :as json]
            [clojure.data.json.jolt-native :as native]
            [jolt.scheme :as scheme]))

(defn- stock [rows]
  (apply str (map #(str (json/write-str %) "\n") rows)))

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

(defn -main [& _]
  (let [r (clojure.test/run-tests 'clojure.data.json-segmented-batch-test)]
    (System/exit (if (zero? (+ (:fail r) (:error r))) 0 1))))

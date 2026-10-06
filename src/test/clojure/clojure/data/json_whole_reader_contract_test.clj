(ns clojure.data.json-whole-reader-contract-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is run-tests]])
  (:import [java.io StringReader]))

(deftest portable-default-and-decline
  (is (nil? json/*experimental-native-reader*))
  (doseq [backend [nil (fn [& _] nil) (fn [& _] false)]]
    (binding [json/*experimental-native-reader* backend]
      (is (= {"a" [1 nil false]} (json/read-str "{\"a\":[1,null,false]}"))))))

(deftest callback-and-reader-exclusion
  (binding [json/*experimental-native-reader* (fn [& _] (throw (Exception. "unexpected backend")))]
    (is (= {:a 1} (json/read-str "{\"a\":1}" :key-fn keyword)))
    (is (= {"a" 2} (json/read-str "{\"a\":1}" :value-fn (fn [_ v] (inc v)))))
    (is (= 42 (json/read (StringReader. "42"))))))

(deftest extra-data-retains-reader-suffix
  (let [calls (atom [])]
    (binding [json/*experimental-native-reader* (fn [& _] [{} 2])]
      (is (= :called (json/read-str "{} tail" :extra-data-fn
                                  (fn [value reader]
                                    (swap! calls conj [value (slurp reader)]) :called)))))
    (is (= [[{} " tail"]] @calls))))

(deftest number-callback-retains-stock-conversions
  (doseq [token ["9223372036854775808" "1.25"] options [[] [:bigdec true]]]
    (let [expected (apply json/read-str token options)
          actual (binding [json/*experimental-native-reader*
                           (fn [source _ number] [(number source) (count source)])]
                   (apply json/read-str token options))]
      (is (= expected actual))
      (is (= (class expected) (class actual))))))

(defn -main [& _]
  (let [result (run-tests 'clojure.data.json-whole-reader-contract-test)]
    (System/exit (if (zero? (+ (:fail result) (:error result))) 0 1))))

(ns clojure.data.json-whole-reader-test
  (:require [clojure.data.json :as json]
            [clojure.data.json.jolt-native :as native]
            [clojure.test :refer [deftest is run-tests]])
  (:import [java.io StringReader]))

(def reader (delay (native/load-reader!)))

(defn outcome [source options selected]
  (binding [json/*experimental-native-reader* selected]
    (try (let [value (apply json/read-str source options)]
           {:value value :class (class value)})
         (catch Exception error
           {:error (class error) :message (.getMessage error)}))))

(deftest native-complete-input-path-is-exercised
  (let [declared (atom [])
        tracked (fn [source options number]
                  (let [result (@reader source options number)]
                    (swap! declared conj (vector? result)) result))]
    (doseq [source ["{}" "[]" "false" "null" "true" "42" "-42"
                    "9223372036854775808" "9007199254740991" "1.25" "1e2"
                    "{\"key\":\"wal\\/1-2-aaaaaaaa.jsonl\"}"
                    "{\"x\":[false,null,{},[1,-2,3.25]],\"unicode\":\"λ😀\"}"]
            options [[] [:bigdec true]]]
      (is (= (outcome source options nil) (outcome source options tracked)) source))
    (is (= 26 (count @declared)))
    (is (every? true? @declared))))

(deftest fallback-preserves-errors-and-duplicate-last-value
  (doseq [source ["" " " "[" "{" "[1,]" "{\"a\":}" "01" "-01"
                  "1e" "1e+" "1.2.3" "true false" "{} trailing"
                  "{\"a\":1,\"a\":2}" "{\"a\":1,\"\\u0061\":2}"
                  "\"\\uZZZZ\"" "\"\\uD800\"" "\"x\\x\""
                  "\"raw\ncontrol\"" "\"escaped\\/raw\ncontrol\""]
          options [[] [:bigdec true] [:eof-error? false :eof-value :eof]]]
    (is (= (outcome source options nil) (outcome source options @reader)) source)))

(deftest strings-retain-host-unicode-and-escape-values
  (doseq [source ["\"\\u0041\"" "\"\\uD83D\\uDE00\""
                  "\"\\b\\f\\n\\r\\t\\/\\\\\\\"\""
                  "\"λ😀é\"" "\"a\\nλ\\u03b2z\""]]
    (is (= (outcome source [] nil) (outcome source [] @reader)))))

(deftest callbacks-and-reader-input-do-not-enter-backend
  (let [blocked (fn [& _] (throw (Exception. "unexpected native route")))]
    (binding [json/*experimental-native-reader* blocked]
      (is (= {:a 1} (json/read-str "{\"a\":1}" :key-fn keyword)))
      (is (= {"a" 2} (json/read-str "{\"a\":1}" :value-fn (fn [_ v] (inc v)))))
      (is (= {"a" 1} (json/read (StringReader. "{\"a\":1}")))))))

(deftest extra-data-callback-retains-exact-unread-content-and-once-only-effects
  (doseq [source ["{}" "{} " "{}\n" "{}tail" "false "]]
    (let [run (fn [selected]
                (let [calls (atom [])]
                  (binding [json/*experimental-native-reader* selected]
                    (let [value (json/read-str source :extra-data-fn
                                              (fn [value reader]
                                                (swap! calls conj [value (slurp reader)])
                                                :callback))]
                      [value @calls]))))]
      (is (= (run nil) (run @reader))))))

(deftest deep-input-declines-without-changing-portable-support
  (let [source (str (apply str (repeat 65 "[")) "0" (apply str (repeat 65 "]")))]
    (is (false? (@reader source {} (fn [_] 0))))
    (is (= (outcome source [] nil) (outcome source [] @reader)))))

(deftest selected-number-conversion-preserves-scalar-classes
  (doseq [token ["0" "-0" "9223372036854775807" "-9223372036854775808"
                 "9223372036854775808" "-9223372036854775809"
                 "1.25" "1e300" "1e-300"]
          options [[] [:bigdec true]]]
    (is (= (outcome token options nil) (outcome token options @reader)) token)))

(deftest declining-and-invalid-backends-are-observable
  (is (= {"a" 1} (binding [json/*experimental-native-reader* (fn [& _] false)]
                   (json/read-str "{\"a\":1}"))))
  (doseq [bad [[42] [42 -1] [42 20] [42 1.5] {:value 42}]]
    (is (= "Invalid experimental JSON whole-reader result"
           (:message (outcome "42" [] (fn [& _] bad)))))))

(defn -main [& _]
  (let [result (run-tests 'clojure.data.json-whole-reader-test)]
    (System/exit (if (zero? (+ (:fail result) (:error result))) 0 1))))

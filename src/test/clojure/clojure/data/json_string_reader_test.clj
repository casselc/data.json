(ns clojure.data.json-string-reader-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]])
  (:import [java.io StringReader]))

(defn- token-result [source start decoder]
  (let [stream (#'json/string-pbr source)]
    (.setPosition stream start)
    (binding [json/*experimental-native-string-reader* decoder]
      (try {:value (#'json/read-quoted-string-from-string stream)
            :position (.position stream)}
           (catch Throwable error
             {:error (.getMessage error) :position (.position stream)})))))

(deftest unbound-reader-remains-portable
  (is (nil? json/*experimental-native-string-reader*))
  (is (= "a\nb" (json/read-str "\"a\\nb\""))))

(deftest declines-resume-at-original-cursor
  (doseq [body ["plain\"tail" "a\\n\"tail" "a\\u0041\"tail"
                "a\\x\"tail" "unterminated" "backslash\\"]
          start [0 2 64]
          decline [nil false]]
    (let [source (str (apply str (repeat start "p")) body)
          calls (atom [])
          decoder (fn [s position]
                    (swap! calls conj [s position]) decline)]
      (is (= (token-result source start nil)
             (token-result source start decoder)))
      (is (= [[source start]] @calls)))))

(deftest selected-reader-result-updates-cursor-once
  (is (= {:value "decoded" :position 6}
         (token-result "ppraw\"tail" 2 (fn [_ _] ["decoded" 6])))))

(deftest invalid-reader-results-cannot-move-cursor
  (doseq [result [true "text" [] ["x"] [nil 6] ["x" 2]
                  ["x" -1] ["x" 100] ["x" 4] ["x" 6 7] ["x" 6.0]]]
    (is (= {:error "Invalid experimental JSON string-reader result" :position 2}
           (token-result "ppraw\"tail" 2 (fn [_ _] result))))))

(deftest reader-backed-input-never-invokes-experimental-decoder
  (let [calls (atom 0)]
    (binding [json/*experimental-native-string-reader*
              (fn [& _] (swap! calls inc) (throw (Exception. "unreachable")))]
      (is (= {"k" "a\nb"}
             (json/read (StringReader. "{\"k\":\"a\\nb\"}")))))
    (is (zero? @calls))))

(deftest opt-in-binding-restores-default-and-preserves-options
  (let [calls (atom 0)]
    (binding [json/*experimental-native-string-reader*
              (fn [_ _] (swap! calls inc) false)]
      (is (= {:k "a\nb"}
             (json/read-str "{\"k\":\"a\\nb\"}" :key-fn keyword
                            :value-fn (fn [_ v] v)))))
    (is (pos? @calls))
    (is (nil? json/*experimental-native-string-reader*))))

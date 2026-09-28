(ns clojure.data.json-string-scan-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]])
  (:import (java.io StringReader)))

(defn- observed-string [source]
  (let [search @#'json/next-string-quote
        calls (atom [])
        value (with-redefs-fn
                {#'json/next-string-quote
                 (fn [text start]
                   (let [found (search text start)]
                     (swap! calls conj
                            {:start start :found found
                             :scanned (if (neg? found)
                                        (- (count text) start)
                                        (inc (- found start)))})
                     found))}
                #(json/read-str source))]
    {:value value :calls @calls}))

(deftest long-nonquote-escapes-search-for-the-closing-quote-once
  (doseq [n [16 64 512 2048]
          [escaped value] [["\\n" "\n"] ["\\\\" "\\"]
                           ["\\u0041" "A"]]]
    (let [source (str "\"" (apply str (repeat n escaped)) "\"")
          result (observed-string source)]
      (is (= (apply str (repeat n value)) (:value result)))
      (is (= (json/read (StringReader. source)) (:value result)))
      (is (= 1 (count (:calls result))))
      (is (<= (reduce + (map :scanned (:calls result))) (count source))))))

(deftest consumed-escaped-quotes-advance-search-ranges-without-overlap
  (doseq [body ["a\\\"b\\n\\\"c"
                "\\\\\\\"\\u0041\\\"\\t"
                (apply str (repeat 512 "prefix\\\"suffix\\n"))]]
    (let [source (str "\"" body "\"")
          result (observed-string source)
          calls (:calls result)]
      (is (= (json/read (StringReader. source)) (:value result)))
      (is (> (count calls) 1) "escaped quotes really invalidate the cached index")
      (is (every? (fn [[previous next]]
                    (< (:found previous) (:start next)))
                  (partition 2 1 calls)))
      (is (<= (reduce + (map :scanned calls)) (count source))))))

(defn- string-result [body]
  (let [stream (#'json/string-pbr (str "pp" body))]
    (.setPosition stream 2)
    (try
      [:value (#'json/read-quoted-string-from-string stream) (.position stream)]
      (catch Throwable error
        [:error (.getMessage error) (.position stream)]))))

(deftest cached-quotes-preserve-cursor-and-malformed-input-boundaries
  (is (= [:value "a\nb\"c" 10] (string-result "a\\nb\\\"c\"tail")))
  (doseq [body ["unterminated" "a\\n" "a\\" "a\\x\""
                "a\\u00xz\"" "a\\u0041"]]
    (is (= :error (first (string-result body)))))
  (is (= [:value "\n\n" 7] (string-result "\\n\\n\"tail"))))

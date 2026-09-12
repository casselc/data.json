(ns clojure.data.json-jolt-test
  "Focused compatibility gate for Jolt's scalar-indexed String semantics."
  (:require [clojure.data.json :as json]))

(def ^:private failures (atom 0))

(deftype CountingAppendable [^StringBuilder builder counts]
  Appendable
  (^Appendable append [this ^char c]
    (swap! counts update :char (fnil inc 0))
    (.append builder c)
    this)
  (^Appendable append [this ^CharSequence chars]
    (swap! counts update :sequence (fnil inc 0))
    (.append builder chars)
    this)
  (^Appendable append [this ^CharSequence chars ^int start ^int end]
    (swap! counts update :range (fnil inc 0))
    (swap! counts update :ranges (fnil conj []) [start end])
    (.append builder chars start end)
    this)
  Object
  (toString [_] (.toString builder)))

(defn- check [label expected actual]
  (if (= expected actual)
    (println "  ok  " label)
    (do
      (swap! failures inc)
      (println "  FAIL" label "expected" (pr-str expected)
               "got" (pr-str actual)))))

(defn- thrown?* [f]
  (try
    (f)
    false
    (catch Throwable _ true)))

(defn- read-result [f]
  (try
    [:value (f)]
    (catch Throwable error
      [:error (.getMessage error)])))

(defn -main [& _]
  (println "data.json scalar-indexed Jolt compatibility")
  (check "host strings are scalar-indexed" 1 (.length "😃"))
  (check "Character.toChars constructs one scalar"
         "😃"
         (String. (Character/toChars 0x1F603)))
  (check "escaped surrogate pair reads as one scalar"
         "😃"
         (json/read-str "\"\\ud83d\\ude03\""))
  (check "raw supplementary scalar reads unchanged"
         "😃"
         (json/read-str "\"😃\""))
  (check "supplementary scalar writes directly"
         "\"😃\""
         (json/write-str "😃" :escape-unicode false))
  (check "supplementary scalar writes as a JSON surrogate pair"
         "\"\\ud83d\\ude03\""
         (json/write-str "😃" :escape-unicode true))
  (check "mixed string round-trips"
         "A\u0000\u2028😃Z"
         (json/read-str (json/write-str "A\u0000\u2028😃Z")))
  (check "object keys and values round-trip"
         {"key-😃" ["value-😃" 0 true nil]}
         (json/read-str
          (json/write-str {"key-😃" ["value-😃" 0 true nil]})))
  (let [plain (apply str (repeat 4096 "a"))
        values [plain
                (str (apply str (repeat 63 "p")) "\"" plain)
                (str (apply str (repeat 64 "p")) "\\" plain)
                (str (apply str (repeat 65 "p")) "\n" plain)
                (str plain "λ😃" plain)]]
    (check "long read-str values match the generic Reader path"
           (mapv (fn [value]
                   (let [encoded (json/write-str value)]
                     (read-result #(json/read
                                   (java.io.StringReader. encoded)))))
                 values)
           (mapv (fn [value]
                   (read-result #(json/read-str (json/write-str value))))
                 values)))
  (let [plain (apply str (repeat 4096 "a"))
        malformed [(str "\"" plain)
                   (str "\"" plain "\\")
                   (str "\"" plain "\\u12")]]
    (check "long malformed strings match the generic Reader errors"
           (mapv #(read-result
                   (fn [] (json/read (java.io.StringReader. %))))
                 malformed)
           (mapv #(read-result (fn [] (json/read-str %))) malformed)))
  (let [value (str (apply str (repeat 4096 "p")) "\\\n😃tail")
        encoded (str (json/write-str value) " remaining")
        remaining
        (try
          (json/read-str encoded :extra-data-fn json/on-extra-throw-remaining)
          nil
          (catch clojure.lang.ExceptionInfo error
            (:remaining (ex-data error))))]
    (check "long read-str preserves the remaining-data position"
           " remaining"
           remaining))
  (let [encoded (json/write-str (apply str (repeat 4096 "a")))
        original @#'json/slow-read-string
        calls (atom 0)
        counted (fn [& args]
                  (swap! calls inc)
                  (apply original args))]
    (with-redefs-fn {#'json/slow-read-string counted}
      #(json/read-str encoded))
    (check "long read-str bypasses the scalar Reader fallback" 0 @calls)
    (with-redefs-fn {#'json/slow-read-string counted}
      #(json/read (java.io.StringReader. encoded)))
    (check "generic Reader retains the scalar fallback" true (pos? @calls)))
  (let [counts (atom {})
        out (CountingAppendable. (StringBuilder.) counts)]
    (#'json/write-string "prefix\"one-long-unescaped-tail" out {})
    (check "unescaped tail is appended as one bulk run"
           ["\"prefix\\\"one-long-unescaped-tail\"" {:char 4 :range 2}]
           [(str out) (select-keys @counts [:char :range])]))
  (let [counts (atom {})
        out (CountingAppendable. (StringBuilder.) counts)]
    (#'json/write-string "\"left😃right" out {:escape-unicode false})
    (check "unescaped astral scalar stays inside one scalar-indexed bulk run"
           ["\"\\\"left😃right\"" {:char 4 :range 1 :ranges [[1 11]]}]
           [(str out) @counts]))
  (check "unpaired high surrogate is rejected"
         true
         (thrown?* #(json/read-str "\"\\ud83d\"")))
  (check "unpaired low surrogate is rejected"
         true
         (thrown?* #(json/read-str "\"\\ude03\"")))
  (if (zero? @failures)
    (println "all checks passed")
    (throw (ex-info "Jolt data.json compatibility failures"
                    {:failures @failures}))))

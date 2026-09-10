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
  (let [counts (atom {})
        out (CountingAppendable. (StringBuilder.) counts)]
    (#'json/write-string "prefix\"one-long-unescaped-tail" out {})
    (check "unescaped tail is appended as one bulk run"
           ["\"prefix\\\"one-long-unescaped-tail\"" {:char 4 :range 2}]
           [(str out) @counts]))
  (check "unpaired high surrogate is rejected"
         true
         (thrown?* #(json/read-str "\"\\ud83d\"")))
  (check "unpaired low surrogate is rejected"
         true
         (thrown?* #(json/read-str "\"\\ude03\"")))
  (if (zero? @failures)
    (println "all 11 checks passed")
    (throw (ex-info "Jolt data.json compatibility failures"
                    {:failures @failures}))))

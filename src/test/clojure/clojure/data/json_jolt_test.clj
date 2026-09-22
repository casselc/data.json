(ns clojure.data.json-jolt-test
  "Focused compatibility gate for Jolt's scalar-indexed String semantics."
  (:require [clojure.data.json :as json])
  (:import (java.io StringWriter)))

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

(defn- stringpbr-string-result [source start]
  (let [stream (#'json/string-pbr source)]
    (.setPosition stream start)
    (try
      [:value (#'json/read-quoted-string-from-string stream)
       (.position stream)]
      (catch Throwable error
      [:error (.getMessage error) (.position stream)]))))

(defn- write-to-string [value & options]
  (let [out (StringWriter.)]
    (apply json/write value out options)
    (.toString out)))

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
  ;; The new fixed arities are deliberately a default-only optimization.  This
  ;; closes over the raw exporter value shapes, including all default escaping,
  ;; while the explicit-option calls retain the original variadic route.
  (let [values ["quote\" slash/ backslash\\ newline\n nul\u0000 bmp-漢 astral-😀"
                nil false 0 -42 9223372036854775807
                {"text" "quote\"\\/\n漢😀"
                 "values" [nil false 0 7]}]]
    (check "no-options write and write-str agree over scalar and nested corpus"
           (mapv (fn [value]
                   (let [expected (json/write-str value :escape-unicode true
                                                   :escape-js-separators true :escape-slash true)]
                     [expected expected]))
                 values)
           (mapv (fn [value] [(json/write-str value) (write-to-string value)]) values))
    (check "explicit write-str options retain variadic override behavior"
           ["\"a/b漢😀\"" "\"a\\/b\\u6f22\\ud83d\\ude00\""]
           [(json/write-str "a/b漢😀" :escape-unicode false :escape-slash false)
            (json/write-str "a/b漢😀" :escape-unicode true :escape-slash true)])
    (check "explicit write options retain variadic override behavior"
           ["\"a/b漢😀\"" "\"a\\/b\\u6f22\\ud83d\\ude00\""]
           [(write-to-string "a/b漢😀" :escape-unicode false :escape-slash false)
            (write-to-string "a/b漢😀" :escape-unicode true :escape-slash true)]))
  (let [escapes [["\\\"" "\""]
                 ["\\\\" "\\"]
                 ["\\/" "/"]
                 ["\\b" (str \backspace)]
                 ["\\f" (str \formfeed)]
                 ["\\n" (str \newline)]
                 ["\\r" (str \return)]
                 ["\\t" (str \tab)]]]
    (check "simple escapes preserve values and nonzero/boundary positions"
           (vec
            (for [[encoded expected] escapes
                  start [2 63 64 65]]
              [:value expected (+ start (count encoded) 1)]))
           (vec
            (for [[encoded _] escapes
                  start [2 63 64 65]
                  :let [source (str (apply str (repeat start "p"))
                                    encoded "\"tail")]]
              (stringpbr-string-result source start)))))
  (check "StringPBR empty and nonempty ordinary runs preserve values"
         ["\n" "a\nb" "\n\tb" "prefix\nsuffix\t"]
         (mapv json/read-str
               ["\"\\n\""
                "\"a\\nb\""
                "\"\\n\\tb\""
                "\"prefix\\nsuffix\\t\""]))
  (let [output (StringBuilder.)]
    (check "StringPBR empty runs do not append a substring" ""
           (do (#'json/append-nonempty-string-run output "abc" 1 1)
               (str output)))
    (check "StringPBR nonempty runs append the selected substring" "bc"
           (do (#'json/append-nonempty-string-run output "abc" 1 3)
               (str output))))
  (check "Unicode, surrogate, and raw astral paths preserve final position"
         [[:value "A" 9]
          [:value "\nA" 11]
          [:value "😃" 15]
          [:value "raw-λ-😃" 10]]
         (mapv (fn [[source start]]
                 (stringpbr-string-result source start))
               [["pp\\u0041\"tail" 2]
                ["pp\\n\\u0041\"tail" 2]
                ["pp\\ud83d\\ude03\"tail" 2]
                ["ppraw-λ-😃\"tail" 2]]))
    (let [fallbacks [["\\q" 4]
                   ["\\" 3]
                   ["\\u12" 6]
                   ["\\n\\q" 6]]]
    (check "malformed and EOF fallbacks retain Reader errors"
           (mapv (fn [[body _]]
                   (read-result #(json/read (java.io.StringReader.
                                             (str "\"" body)))))
                 fallbacks)
           (mapv (fn [[body _]]
                   (let [[tag message _]
                         (stringpbr-string-result (str "pp" body) 2)]
                     [tag message]))
                 fallbacks))
    (check "malformed and EOF fallbacks retain StringPBR positions"
           (mapv second fallbacks)
           (mapv (fn [[body _]]
                   (nth (stringpbr-string-result (str "pp" body) 2) 2))
                 fallbacks)))
  (let [simple-body (apply str ["\\\"" "\\\\" "\\/" "\\b"
                                "\\f" "\\n" "\\r" "\\t"])
        simple-encoded (str "\"" simple-body "\"")
        original @#'json/read-escaped-char
        calls (atom 0)
        counted (fn [& args]
                  (swap! calls inc)
                  (apply original args))]
    (with-redefs-fn {#'json/read-escaped-char counted}
      #(json/read-str simple-encoded))
    (check "StringPBR simple escapes avoid per-escape Reader decoding" 0 @calls)
    (reset! calls 0)
    (with-redefs-fn {#'json/read-escaped-char counted}
      #(json/read (java.io.StringReader. simple-encoded)))
    (check "ReaderPBR simple escapes retain per-escape Reader decoding" 8 @calls)
    (reset! calls 0)
    (with-redefs-fn {#'json/read-escaped-char counted}
      #(json/read-str "\"\\u0041\""))
    (check "StringPBR Unicode retains the shared escape decoder" 1 @calls)
    (doseq [[label encoded]
            [["invalid escape" "\"\\q"]
             ["escape EOF" "\"\\"]]]
      (reset! calls 0)
      (try
        (with-redefs-fn {#'json/read-escaped-char counted}
          #(json/read-str encoded))
        (catch Throwable _ nil))
      (check (str "StringPBR " label " retains shared decoder") 1 @calls)))
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

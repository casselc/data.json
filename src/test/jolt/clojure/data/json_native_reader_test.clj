(ns clojure.data.json-native-reader-test
  (:require [clojure.data.json :as json]
            [clojure.data.json.jolt-native :as native]
            [clojure.java.io :as io]
            [clojure.data.json-string-reader-test]
            [clojure.data.json-string-scan-test]
            [jolt.fibers :as fibers]
            [jolt.scheme :as scheme]
            [clojure.test :refer [deftest is run-tests]]))

(def reader (delay (native/load-string-reader!)))

(defn- token-outcome [source start decoder]
  (let [stream (#'json/string-pbr source)]
    (.setPosition stream start)
    (binding [json/*experimental-native-string-reader* decoder]
      (try {:value (#'json/read-quoted-string-from-string stream)
            :position (.position stream)}
           (catch Throwable error
             {:error-class (class error) :error (.getMessage error)
              :position (.position stream)})))))

(deftest valid-unicode-is-selected-not-silently-fallback
  (doseq [cp [0 31 127 128 255 2047 2048 55295 57344 65535 65536 128512 1114111]
          :let [text (str "left" (char cp) "right")
                token (subs (json/write-str text :escape-unicode true) 1)]]
    (is (= [text (count token)] (@reader token 0)))
    (doseq [start [0 2 63 64 65]
            :let [source (str (apply str (repeat start "p")) token "tail")]]
      (is (= (token-outcome source start nil)
             (token-outcome source start @reader)))))
  (doseq [token ["\\u00aF\"" "\\u00Af\"" "\\udBff\\uDfff\""
                "a\\n\\u03B2\\t\\uD83D\\uDE00z\""]]
    (is (vector? (@reader token 0)))
    (is (= (token-outcome token 0 nil) (token-outcome token 0 @reader)))))

(deftest malformed-and-noncanonical-unicode-retain-exact-errors-and-cursors
  (doseq [body ["\\u" "\\u1" "\\u12" "\\u123" "\\uZZZZ\""
                "\\uD800" "\\uD800x\"" "\\uD800\\x0000\""
                "\\uD800\\u" "\\uD800\\u12" "\\uD800\\u0000\""
                "\\uD800\\uD800\"" "\\uDC00\"" "\\uDFFF\""
                "\\u+041\"" "\\u-001\"" "a\\u0041\\uDC00\""]
          start [0 2 64]
          :let [source (str (apply str (repeat start "p")) body)]]
    (is (false? (@reader source start)))
    (is (= (token-outcome source start nil)
           (token-outcome source start @reader)))))

(deftest decline-categories-are-explicit-and-do-not-mutate-source
  (doseq [[category body] [[:unicode "a\\uZZZZ\"tail"]
                         [:invalid "a\\x\"tail"]
                         [:string-eof "unterminated"]
                         [:escape-eof "trailing\\"]]
          start [0 2 64]]
    (let [source (str (apply str (repeat start "p")) body)
          before (vec (map int source))
          stream (#'json/string-pbr source)]
      (.setPosition stream start)
      (is (false? (@reader (.sourceString stream) (.position stream))) (name category))
      (is (= start (.position stream)) "decline cannot move the reader")
      (is (= before (vec (map int source))) "decline cannot mutate source"))))

(deftest overlapping-readers-have-isolated-bindings-and-owned-results
  (let [left-ready (promise) right-ready (promise) release (promise)
        calls (atom {})
        run (fn [label ready source]
              (fibers/spawn
               (fn []
                 (binding [json/*experimental-native-string-reader*
                           (fn [s start]
                             (swap! calls update label (fnil inc 0))
                             (@reader s start))]
                   (deliver ready true)
                   @release
                   (json/read-str source)))))
        left (run :left left-ready "\"left\\n\"")
        right (run :right right-ready "\"right\\t\"")]
    @left-ready @right-ready
    (is (nil? json/*experimental-native-string-reader*))
    (is (= "main" (json/read-str "\"main\"")))
    (deliver release true)
    (is (= "left\n" (fibers/join left)))
    (is (= "right\t" (fibers/join right)))
    (is (= {:left 1 :right 1} @calls)))
  (let [first-token (@reader "one\\n\"" 0)]
    (doseq [n [1 64 2048]]
      (@reader (str (apply str (repeat n "different\\t")) "\"") 0))
    (is (= ["one\n" 6] first-token))))

(deftest native-token-values-options-and-fallback-parity
  (doseq [i (range 256) unicode? [true false]
          :let [text (json/write-str {"key" (str "prefix" (char i) "\"/\\suffix")}
                                    :escape-unicode unicode?)]]
    (is (= (json/read-str text :key-fn keyword)
           (binding [json/*experimental-native-string-reader* @reader]
             (json/read-str text :key-fn keyword)))))
  (is (nil? json/*experimental-native-string-reader*))
  (is (true? (:loaded? (native/string-reader-info))))
  (is (false? (:enabled-by-default? (native/string-reader-info)))))

(defn- counting-reader [bad-prefix-rescan?]
  ;; Lexically intercept actual source reads and emitted copies in the SAME
  ;; production kernel. No formula-derived visit count substitutes for work.
  ;; The known-bad mode actually rereads each prefix before each requested read.
  (let [source (slurp (io/resource "clojure/data/json/jolt_string_reader.ss"))]
    (scheme/eval-string
     (str "(let ((ref string-ref) (put put-string) (putc put-char) (slice substring)\n"
          "       (copy string-copy!) (set string-set!) (make make-string))\n"
          " (lambda (s start)\n"
          "  (let ((visits 0) (copied 0) (allocations '()))\n"
          "   (let ((decode (let ((string-ref (lambda (text i)\n"
          (when bad-prefix-rescan?
            "    (do ((j 0 (fx+ j 1))) ((fx> j i)) (set! visits (fx+ visits 1)) (ref text j))\n")
          "    (set! visits (fx+ visits 1)) (ref text i)))\n"
          "    (put-string (lambda (p text start count) (set! copied (fx+ copied count)) (put p text start count)))\n"
          "    (put-char (lambda (p ch) (set! copied (fx+ copied 1)) (putc p ch)))\n"
          "    (string-copy! (lambda (text start to at count) (set! copied (fx+ copied count)) (copy text start to at count)))\n"
          "    (string-set! (lambda (to at ch) (set! copied (fx+ copied 1)) (set to at ch)))\n"
          "    (make-string (lambda (n) (set! allocations (cons n allocations)) (make n)))\n"
          "    (substring (lambda (text start end) (set! copied (fx+ copied (fx- end start))) (slice text start end))))\n"
          source ")))\n"
          "    (let ((result (decode s start))) (jolt-vector result visits copied (apply jolt-vector allocations)))))))"))))

(deftest escaped-tokens-allocate-one-exact-owned-result
  (let [decode (counting-reader false)]
    (doseq [n [1 63 64 65 4096]
            body ["ordinary\\n" "\\\\" "\\\"" "\\u0041" "\\uD83D\\uDE00"]
            :let [source (str "pp" (apply str (repeat n body)) "\"tail")
                  [result _ copied allocations] (decode source 2)]]
      (is (= (token-outcome source 2 nil) (token-outcome source 2 @reader)))
      (is (= [(count (first result))] allocations))
      (is (= (count (first result)) copied)))
    (doseq [source ["plain\"" "\"" "a\\n\\x\"" "a\\uD800\"" "a\\nunterminated"]
            :let [[_ _ _ allocations] (decode source 0)]]
      (is (empty? allocations) "plain or declined tokens need no escaped output allocation"))))

(deftest valid-extraction-never-writes-the-source-or-reuses-result-storage
  (doseq [body ["prefix\\nmid\\tend" "\\u0041suffix" "head\\uD83D\\uDE00tail"]
          start [0 2 64]
          :let [source (str (apply str (repeat start "p")) body "\"tail")
                before (vec (map int source))
                expected (token-outcome source start nil)
                result (@reader source start)]]
    (is (= before (vec (map int source))))
    (is (= [(:value expected) (:position expected)] result))
    (@reader "a different\\nresult\"" 0)
    (is (= [(:value expected) (:position expected)] result))))

(deftest native-work-is-linear-and-counter-rejects-real-prefix-rescanning
  (let [good (counting-reader false)
        bad (counting-reader true)
        within-bound? (fn [source [_ visits copied]]
                        (and (<= visits (+ 8 (* 4 (count source))))
                             (<= copied (count source))))]
    (doseq [n [16 64 512 2048]
            body ["a" "\\n" "\\\\" "\\\"" "ordinary\\t" "\\u0041"
                  "\\uD83D\\uDE00"]
            :let [source (str (apply str (repeat n body)) "\"tail")
                  [result :as observed] (good source 0)]]
      (is (= (@reader source 0) result))
      (is (within-bound? source observed)))
    (let [source (str (apply str (repeat 64 "a")) "\"")
          good-result (good source 0)
          bad-result (bad source 0)]
      (is (= (first good-result) (first bad-result)))
      (is (within-bound? source good-result))
      (is (not (within-bound? source bad-result)) "real quadratic work makes the oracle red"))))

(defn -main [& _]
  (let [result (run-tests 'clojure.data.json-native-reader-test
                          'clojure.data.json-string-reader-test
                          'clojure.data.json-string-scan-test)]
    (System/exit (if (zero? (+ (:fail result) (:error result))) 0 1))))

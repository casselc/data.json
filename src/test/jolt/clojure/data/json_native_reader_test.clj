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

(deftest decline-categories-are-explicit-and-do-not-mutate-source
  (doseq [[category body] [[:unicode "a\\u0041\"tail"]
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
     (str "(let ((ref string-ref) (put put-string) (putc put-char) (slice substring))\n"
          " (lambda (s start)\n"
          "  (let ((visits 0) (copied 0))\n"
          "   (let ((decode (let ((string-ref (lambda (text i)\n"
          (when bad-prefix-rescan?
            "    (do ((j 0 (fx+ j 1))) ((fx> j i)) (set! visits (fx+ visits 1)) (ref text j))\n")
          "    (set! visits (fx+ visits 1)) (ref text i)))\n"
          "    (put-string (lambda (p text start count) (set! copied (fx+ copied count)) (put p text start count)))\n"
          "    (put-char (lambda (p ch) (set! copied (fx+ copied 1)) (putc p ch)))\n"
          "    (substring (lambda (text start end) (set! copied (fx+ copied (fx- end start))) (slice text start end))))\n"
          source ")))\n"
          "    (let ((result (decode s start))) (jolt-vector result visits copied))))))"))))

(deftest native-work-is-linear-and-counter-rejects-real-prefix-rescanning
  (let [good (counting-reader false)
        bad (counting-reader true)
        within-bound? (fn [source [_ visits copied]]
                        (and (<= visits (+ 8 (* 4 (count source))))
                             (<= copied (count source))))]
    (doseq [n [16 64 512 2048]
            body ["a" "\\n" "\\\\" "\\\"" "ordinary\\t"]
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

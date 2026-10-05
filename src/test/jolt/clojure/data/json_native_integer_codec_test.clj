(ns clojure.data.json-native-integer-codec-test
  (:require [clojure.test :as t :refer [deftest is]]
            [clojure.data.json :as json]
            [clojure.data.json.jolt-native :as native]
            [jolt.scheme :as scheme]))

(native/load-writer!)

(defn- encode [value]
  (binding [json/*experimental-native-writer* (native/load-payload-writer!)]
    (json/write-str value)))

(defn- portable [value]
  (binding [json/*experimental-native-writer* nil]
    (json/write-str value)))

(deftest optional-codec-is-selected-and-bigints-decline
  (let [check
        (scheme/eval-string
         "(lambda (operation)
            (let ((original djn-fixnum-renderer) (calls 0))
              (unless original (error 'codec \"selected runtime lacks codec\"))
              (dynamic-wind
                (lambda ()
                  (set! djn-fixnum-renderer
                    (lambda (n) (set! calls (+ calls 1)) (original n))))
                (lambda () (jolt-invoke0 operation) calls)
                (lambda () (set! djn-fixnum-renderer original)))))")]
    (is (= 3 (check #(is (= "[0,-17,999,9223372036854775807,-9223372036854775808]"
                           (encode [0 -17 999 Long/MAX_VALUE Long/MIN_VALUE]))))))))

(deftest disabled-capability-retains-the-general-formatter
  (let [disable
        (scheme/eval-string
         "(lambda (operation)
            (let ((original djn-fixnum-renderer))
              (dynamic-wind
                (lambda () (set! djn-fixnum-renderer #f))
                (lambda () (jolt-invoke0 operation))
                (lambda () (set! djn-fixnum-renderer original)))))")
        values [0 -1 10 -10 9007199254740993 Long/MIN_VALUE Long/MAX_VALUE]]
    (is (= (portable values) (disable #(encode values))))))

(deftest boundaries-and-bignums-keep-exact-text
  (let [extreme (scheme/eval-string
                 "(lambda (positive?) (if positive? (most-positive-fixnum) (most-negative-fixnum)))")
        values [0 1 -1 9 -9 10 -10 99 -99 100 -100 (extreme true) (extreme false)
                9007199254740993 Long/MIN_VALUE Long/MAX_VALUE
                (bigint "123456789012345678901234567890")]]
    (is (= (portable values) (encode values)))))

(deftest live-integer-extension-is-not-bypassed
  (let [stock @#'json/write-plain]
    (try
      (extend Long json/JSONWriter {:-write (fn [_ out _] (.append out "\"custom-long\""))})
      (is (= (portable [7]) (encode [7])))
      (is (= "[\"custom-long\"]" (encode [7])))
      (finally (extend Long json/JSONWriter {:-write stock})))))

(defn -main [& _]
  (let [{:keys [fail error]} (t/run-tests 'clojure.data.json-native-integer-codec-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))

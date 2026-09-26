(ns clojure.data.json-native-test
  "Focused source-only Jolt backend contract. Run in a fresh process."
  (:require [clojure.data.json :as json]
            [clojure.data.json.jolt-native :as native]
            [jolt.fibers :as fibers]
            [jolt.scheme :as scheme]
            [clojure.test :refer [deftest is run-tests testing]])
  (:import (java.io StringWriter)))

(def ^:private stock-string @#'json/write-string)

(defn- restore-string! []
  ;; This dedicated test process leaves String's behavior at the stock writer.
  (extend String json/JSONWriter {:-write stock-string}))

(def ^:private prepared
  (delay
    ;; Whichever test runs first, initialize under a pre-existing override. A
    ;; loader that snapshots currently resolved methods as stock fails here.
    (extend String json/JSONWriter
            {:-write (fn [_ out _] (.append out "\"before-init\""))})
    (try
      (let [writer (native/load-writer!)]
        {:writer writer
         :before-init (binding [json/*experimental-native-writer* writer]
                        (json/write-str ["input"]))})
      (finally (restore-string!)))))

(defn- encode [value & options]
  (binding [json/*experimental-native-writer* (:writer @prepared)]
    (apply json/write-str value options)))

(defn- portable [value & options]
  (binding [json/*experimental-native-writer* nil]
    (apply json/write-str value options)))

(defn- outcome [f]
  (try [:value (f)]
       (catch Throwable error [:error (.getMessage error)])))

(deftype Callback [f]
  json/JSONWriter
  (-write [_ out options] (f out options)))

(def ^:private counted-native-call
  (delay
    ;; Test-only interception. There is no counter/observer branch in production
    ;; emission. dynamic-wind restores the binding even when the thunk throws.
    ;; This global interception requires a fresh process and serial test-vars
    ;; execution (the ordinary clojure.test runner); never parallelize this test.
    (scheme/eval-string
      "(lambda (thunk)
         (let ((original djn-string) (calls 0))
           (dynamic-wind
             (lambda ()
               (set! djn-string
                 (lambda (value writer flags)
                   (set! calls (+ calls 1))
                   (original value writer flags))))
             (lambda ()
               (let ((result (jolt-invoke0 thunk)))
                 (jolt-vector result calls)))
             (lambda () (set! djn-string original)))))")))

(deftest native-selection-is-not-vacuous
  (force prepared)
  (is (= {:backend :guarded-chez :loaded? true :enabled-by-default? false
          :required-helper "pmap-fold-seq-order"
          :resource "clojure/data/json/jolt_native.ss"
          :aot-qualified? false :guard :uncached-per-value :sink :string-writer}
         (native/backend-info)))
  (is (= ["{\"a\":[\"b\",1]}" 2]
         (@counted-native-call #(encode (array-map "a" ["b" 1])))))
  (let [calls (atom 0)
        custom (Callback. (fn [out _]
                            (swap! calls inc)
                            (.append out "\"custom\"")))]
    (is (= ["\"custom\"" 0] (@counted-native-call #(encode custom))))
    (is (= 1 @calls)))
  (is (= [(portable {"a" "b"} :indent true) 0]
         (@counted-native-call #(encode {"a" "b"} :indent true))))
  (is (= ["[\"a\",\"b\"]" 0]
         (@counted-native-call #(encode (lazy-seq (list "a" "b")))))))

(deftest library-resource-abi-fails-before-output
  (let [writer (:writer @prepared)
        stock @#'json/native-writer-stock]
    (doseq [bad-stock [nil [] (subvec stock 0 10) (conj stock :extra) (assoc stock 10 :wrong-version)]]
      (let [out (StringWriter.)]
        (.append out "prefix")
        (is (= [:error "data.json native writer ABI mismatch"]
               (outcome #(writer ["value"] out json/default-write-options bad-stock))))
        (is (= "prefix" (.toString out)))))))

(deftest stock-float-and-date-success
  (doseq [[label value expected]
          [[:float (Float/valueOf "1.25") "1.25"]
           [:date (java.util.Date. 0) "\"1970-01-01T00:00:00Z\""]]]
    ;; Fixed known-success outputs prevent equal exceptions from satisfying the
    ;; portable/candidate comparison. Jolt models Float as a native flonum.
    (is (= [:value expected] (outcome #(portable value))) (str label))
    (is (= [:value expected] (outcome #(encode value))) (str label)))
  (is (= ["\"1970-01-01T00:00:00Z\"" 0]
         (@counted-native-call #(encode (java.util.Date. 0))))))

(deftest standard-values-and-options
  (let [collision-map (assoc (into {} (map (fn [n] [(str "pad" n) n]) (range 12)))
                             "x" 101 :a/x 202 :b/x 303)
        values [nil true false "" "ascii" "quote\"/\\\n漢😀\u2028\u2029"
                (apply str (map char (range 32)))
                0 -42 9223372036854775807 -9223372036854775808
                9223372036854775808N -0.0 1.0e-7 1.0e20
                0.001 0.0001 9999999.0 1.0e7
                4.9e-324 1.7976931348623157e308
                :a/name 'b/name [] {} [1 nil ["nested"]]
                (array-map "a" [true 1] :b "value") collision-map
                {7 "numeric key" false "boolean key"} 1/3 1.25M #{1 2}]
        options [[] [:escape-unicode false :escape-slash false]
                 [:escape-unicode true :escape-js-separators false]
                 [:indent true] [:escape-unicode nil]
                 [:unrecognized-option :portable]]]
    (doseq [value values opts options]
      (is (= (outcome #(apply portable value opts))
             (outcome #(apply encode value opts)))
          (str "value " (pr-str value) " options " (pr-str opts)))))
  (doseq [value [{nil 1} \x Double/NaN Double/POSITIVE_INFINITY]]
    (is (= (outcome #(portable value)) (outcome #(encode value)))))
  (let [sentinel (:value-fn json/default-write-options)
        value (array-map "drop" sentinel "keep" 2)]
    (is (= "{\"keep\":2}" (portable value) (encode value)))))

(deftest preexisting-and-warmed-extensions
  (is (= "[\"before-init\"]" (:before-init @prepared)))
  (dotimes [_ 4] (is (= "[\"stock\"]" (encode ["stock"]))))
  (try
    (extend String json/JSONWriter
            {:-write (fn [_ out _] (.append out "\"after-warmup\""))})
    (is (= (portable ["stock"]) (encode ["stock"]) "[\"after-warmup\"]"))
    (finally (restore-string!))))

(deftest same-sink-reentrancy-and-effects
  (let [seen (atom [])
        callback (Callback.
                   (fn [out _]
                     (swap! seen conj [out (.toString out)])
                     ;; Ranged append on the SAME real StringWriter, followed
                     ;; by a nested public write-str using the same binding.
                     (.append out "[")
                     (.append out "x7y" 1 2)
                     (.append out ",")
                     (.append out (json/write-str ["inner"]))
                     (.append out "]")))]
    (is (= "[1,[7,[\"inner\"]],2,[7,[\"inner\"]]]"
           (encode [1 callback 2 callback])))
    (is (= 2 (count @seen)))
    (is (instance? StringWriter (ffirst @seen)))
    (is (identical? (ffirst @seen) (first (second @seen))))
    (is (= ["[1," "[1,[7,[\"inner\"]],2,"] (mapv second @seen))))
  (let [calls (atom 0)
        callback (Callback. (fn [out _]
                              (swap! calls inc)
                              (.append out "7")
                              (throw (ex-info "custom failure" {}))))]
    (is (= [:error "custom failure"] (outcome #(encode [1 callback 2]))))
    (is (= 1 @calls))))

(deftest changing-dispatch-during-emission
  (try
    (let [change (Callback.
                   (fn [out _]
                     ;; Join a distinct thread's registration before the next
                     ;; value. A per-batch snapshot emits the wrong later value.
                     @(future
                        (extend String json/JSONWriter
                                {:-write (fn [_ out _] (.append out "\"changed\""))}))
                     (.append out "0")))]
      (is (= "[\"before\",0,\"changed\"]"
             (encode ["before" change "after"]))))
    (finally (restore-string!)))
  (let [original @#'json/-write
        change (Callback. (fn [out _]
                            (alter-var-root #'json/-write
                                            (constantly (fn [_ out _] (.append out "42"))))
                            (.append out "0")))]
    (try
      (is (= "[1,0,42]" (encode [1 change "after"])))
      (finally (alter-var-root #'json/-write (constantly original))))))

(deftest overlapping-encodes-have-distinct-sinks
  (let [writer (:writer @prepared)
        left-ready (promise)
        right-ready (promise)
        release (promise)
        start (fn [label ready]
                (future
                  (try
                    (binding [json/*experimental-native-writer* writer]
                      (json/write-str
                        [label (Callback.
                                 (fn [out _]
                                   (deliver ready {:writer out :prefix (.toString out)})
                                   @release
                                   (.append out "0")))]))
                    (catch Throwable error
                      ;; Failure before reaching the callback must also unblock
                      ;; the coordinating thread; both futures are joined below.
                      (deliver ready {:error (.getMessage error)})
                      (throw error)))))
        left (start "left" left-ready)
        right (start "right" right-ready)]
    (try
      (let [a @left-ready b @right-ready]
        (is (= "[\"left\"," (:prefix a)))
        (is (= "[\"right\"," (:prefix b)))
        (is (instance? StringWriter (:writer a)))
        (is (instance? StringWriter (:writer b)))
        (is (not (identical? (:writer a) (:writer b)))))
      (finally (deliver release true)))
    ;; Collect both outcomes before asserting either, including failure paths.
    (let [a (outcome #(deref left)) b (outcome #(deref right))]
      (is (= [:value "[\"left\",0]"] a))
      (is (= [:value "[\"right\",0]"] b)))))

(deftest public-var-redefinitions
  (force prepared)
  (with-redefs [json/-write (fn [_ out _] (.append out "42"))]
    (is (= "42" (encode ["value"]))))
  (is (= "[\"value\"]" (encode ["value"])))
  (with-redefs [json/write-str (fn [& _] "public replacement")]
    (is (= "public replacement" (encode ["value"]))))
  (is (= "[\"value\"]" (encode ["value"]))))

(deftest portable-callbacks-and-lazy-order
  (let [run (fn [writer]
              (let [events (atom [])
                    key-fn (fn [k] (swap! events conj [:key k]) (name k))
                    value-fn (fn skip [k v]
                               (swap! events conj [:value k v])
                               (if (nil? v) skip v))]
                [(writer (array-map :a nil :b 2) :key-fn key-fn :value-fn value-fn)
                 @events]))]
    (is (= (run portable) (run encode))))
  (let [run (fn [writer]
              (let [events (atom [])
                    first-value (Callback. (fn [out _]
                                             (swap! events conj :write-first)
                                             (.append out "1")))
                    values (lazy-seq
                             (swap! events conj :realize-first)
                             (cons first-value
                                   (lazy-seq (swap! events conj :realize-next)
                                             (list 2))))]
                [(writer values) @events]))]
    (is (= ["[1,2]" [:realize-first :realize-next :write-first]]
           (run portable) (run encode)))))

(def ^:private observe-scratch-call
  (delay
    ;; Serial/fresh-process mechanism gate, like counted-native-call above.
    ;; It observes the private call-local scratch AFTER each scalar, never adds
    ;; an observer branch to production, and restores the binding on failure.
    (scheme/eval-string
      "(lambda (thunk)
         (let ((original djn-string) (ports '()) (mu (make-mutex)))
           (dynamic-wind
             (lambda ()
               (set! djn-string
                 (lambda (value writer flags)
                   (let ((result (original value writer flags)))
                     (jolt-with-mutex mu
                       (set! ports
                         (cons (and (> (vector-length flags) 3)
                                    (vector-ref flags 3)
                                    (car (vector-ref flags 3))) ports)))
                     result))))
             (lambda ()
               (let* ((result (jolt-invoke0 thunk))
                      (valid (filter port? ports))
                      (unique
                        (let loop ((xs valid) (seen '()))
                          (cond ((null? xs) seen)
                                ((memq (car xs) seen) (loop (cdr xs) seen))
                                (else (loop (cdr xs) (cons (car xs) seen)))))))
                 (jolt-vector result (length ports) (length unique)
                   (= (length ports) (length valid))
                   (for-all port-closed? valid))))
             (lambda () (set! djn-string original)))))")))

(deftest scratch-reuse-extraction-and-reentrancy
  (force prepared)
  (let [values ["first" "" (apply str (repeat 257 "λ")) "x" "after/😀"]]
    ;; Earlier StringWriter chunks must not mutate when the extractor is reused
    ;; for empty, shorter, longer and Unicode strings.
    (is (= [(portable values) 5 1 true true]
           (@observe-scratch-call #(encode values)))))
  (is (= ["[1,true,null]" 0 0 true true]
         (@observe-scratch-call #(encode [1 true nil]))))
  (let [prefix (atom nil)
        nested (Callback. (fn [out _]
                            (reset! prefix (.toString out))
                            (.append out (json/write-str ["inner" ""]))))]
    (is (= ["[\"left\",[\"inner\",\"\"],\"right\"]" 4 2 true true]
           (@observe-scratch-call #(encode ["left" nested "right"]))))
    (is (= "[\"left\"," @prefix))))

(deftest scratch-closes-on-custom-writer-exception
  (let [sink (atom nil)
        fail (Callback. (fn [out _]
                          (reset! sink out)
                          (.append out "0")
                          (throw (ex-info "scratch failure" {}))))]
    (is (= [[:error "scratch failure"] 1 1 true true]
           (@observe-scratch-call #(outcome (fn [] (encode ["first" fail]))))))
    (is (= "[\"first\",0" (.toString @sink)))
    (is (= ["[\"next\"]" 1 1 true true]
           (@observe-scratch-call #(encode ["next"]))))))

(deftest extracted-string-does-not-alias-later-scalars
  (force prepared)
  (let [probe
        (scheme/eval-string
          "(lambda ()
             (let ((flags (vector #t #t #t #f)))
               (dynamic-wind
                 (lambda () (void))
                 (lambda ()
                   (let* ((a (djn-with-string-output flags
                               (lambda (out) (put-string out \"scalar A/λ😀\"))))
                          (first-port (car (vector-ref flags 3)))
                          (b (djn-with-string-output flags
                               (lambda (out) (put-string out \"B\"))))
                          (c (djn-with-string-output flags
                               (lambda (out) (put-string out (make-string 1025 #\\C))))))
                     (jolt-vector a b (string-length c)
                       (eq? first-port (car (vector-ref flags 3))))))
                 (lambda () (close-port (car (vector-ref flags 3)))))))")]
    (is (= ["scalar A/λ😀" "B" 1025 true] (probe)))))

(deftest overlapping-calls-have-distinct-scratch
  (force prepared)
  (let [probe (fn []
                (let [writer (:writer @prepared)
                      ready-a (promise) ready-b (promise) release (promise)
                      start (fn [label ready]
                              (future
                                (try
                                  (binding [json/*experimental-native-writer* writer]
                                    (json/write-str
                                      [label (Callback. (fn [out _]
                                                          (deliver ready true)
                                                          @release
                                                          (.append out "0")))]))
                                  (catch Throwable error
                                    (deliver ready false)
                                    (throw error)))))
                      a (start "a" ready-a) b (start "b" ready-b)]
                  (try
                    (is (true? @ready-a))
                    (is (true? @ready-b))
                    (finally (deliver release true)))
                  ;; Join both even if either one failed.
                  [(outcome #(deref a)) (outcome #(deref b))]))]
    (is (= [[[:value "[\"a\",0]"] [:value "[\"b\",0]"]] 2 2 true true]
           (@observe-scratch-call probe)))))

(deftest scratch-survives-fiber-yield
  (force prepared)
  ;; A fiber park is not a lexical exit. In particular, a custom writer may
  ;; yield after an earlier scalar created scratch and before a later scalar
  ;; needs it again. Observe from the calling thread, not the yielding fiber.
  (let [calls (atom 0)
        prefix (atom nil)
        callback (Callback. (fn [out _]
                              (swap! calls inc)
                              (reset! prefix (.toString out))
                              (fibers/yield)
                              (.append out "0")))]
    (is (= ["[\"before\",0,\"after\"]" 2 1 true true]
           (@observe-scratch-call
            #(fibers/join
              (fibers/spawn (fn [] (encode ["before" callback "after"])))))))
    (is (= 1 @calls))
    (is (= "[\"before\"," @prefix)))
  (let [sink (atom nil)
        callback (Callback. (fn [out _]
                              (reset! sink out)
                              (fibers/yield)
                              (.append out "0")
                              (throw (ex-info "after yield" {}))))]
    (is (= [[:error "after yield"] 1 1 true true]
           (@observe-scratch-call
            #(outcome
              (fn []
                (fibers/join
                 (fibers/spawn (fn [] (encode ["before" callback])))))))))
    (is (= "[\"before\",0" (.toString @sink)))))

(deftest scratch-prefix-remains-stable-across-growth
  (let [prefix (atom nil)
        large (apply str (repeat 65537 "x"))
        callback (Callback. (fn [out _]
                              (reset! prefix (.toString out))
                              (.append out "0")))]
    (is (= (str "[\"a\",0,\"" large "\",\"tail\"]")
           (encode ["a" callback large "tail"])))
    ;; This captured string must survive later extraction and scratch close.
    (is (= "[\"a\"," @prefix))))

(defn -main [& _]
  (let [result (run-tests 'clojure.data.json-native-test)]
    (System/exit (if (zero? (+ (:fail result) (:error result))) 0 1))))

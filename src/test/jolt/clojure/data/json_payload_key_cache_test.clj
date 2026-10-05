(ns clojure.data.json-payload-key-cache-test
  (:require [clojure.data.json :as json]
            [clojure.data.json.jolt-native :as native]
            [jolt.fibers :as fibers]
            [jolt.scheme :as scheme]
            [clojure.test :as test :refer [deftest is]]))

(defn- open-writer []
  (native/load-writer!)
  ((scheme/proc "djn-make-key-cache-writer")))

(defn- encode [writer value options]
  (binding [json/*experimental-native-writer* writer]
    (json/write-str value options)))

(defn- portable [value options]
  (binding [json/*experimental-native-writer* nil]
    (json/write-str value options)))

(deftype Callback [f]
  json/JSONWriter
  (-write [_ out options] (f out options)))

(deftest flags-collisions-growth-and-omission
  (let [writer (open-writer)
        key "quote\"/\\\nβ😀\u2028"
        values [(array-map key 1 "plain" false)
                (array-map :name "first" "name" "last")
                (array-map "name" "first" :name "last")
                (into {} (map (fn [i] [(str "key" i) i]) (range 256)))
                {(apply str (repeat 129 "x")) "long-key"}
                {"omit" (:value-fn json/default-write-options) "keep" nil}]]
    (dotimes [_ 2]
      (doseq [opts [{} {:escape-unicode false} {:escape-slash false}
                    {:escape-js-separators false} {}]
              value values]
        (is (= (portable value opts) (encode writer value opts)))))))

(deftest live-string-extension-after-key-cache-warmup
  (let [writer (open-writer) stock @#'json/write-string]
    (is (= (portable {"key" "before"} {}) (encode writer {"key" "before"} {})))
    (try
      (extend String json/JSONWriter {:-write (fn [_ out _] (.append out "\"changed\""))})
      (is (= "{\"key\":\"changed\"}" (encode writer {"key" "after"} {})))
      (is (= (portable {"key" "after"} {}) (encode writer {"key" "after"} {})))
      (finally (extend String json/JSONWriter {:-write stock})))))

(deftest callbacks-keep-the-real-sink-prefix-and-run-once
  (let [writer (open-writer) prefixes (atom []) calls (atom 0)
        callback (Callback. (fn [out _]
                              (swap! calls inc)
                              (swap! prefixes conj (.toString out))
                              (.append out "7")))
        value (array-map "first" 1 "second" callback)]
    (dotimes [_ 2]
      (is (= "{\"first\":1,\"second\":7}" (encode writer value {}))))
    (is (= 2 @calls))
    (is (= ["{\"first\":1,\"second\":" "{\"first\":1,\"second\":"] @prefixes))))

(deftest custom-options-keep-callback-order-and-errors
  (let [writer (open-writer) events (atom [])
        options {:key-fn (fn [key] (swap! events conj [:key key]) (str "prefix-" key))
                 :value-fn (fn [key value] (swap! events conj [:value key value]) value)}
        value (array-map "a" 1 "b" 2)
        expected (portable value options)
        expected-events @events]
    (reset! events [])
    (is (= expected (encode writer value options)))
    (is (= expected-events @events))
    (let [failure (fn [f] (try (f) :returned (catch Throwable _ :threw)))]
      (is (= :threw (failure #(portable {nil 1} {}))
             (failure #(encode writer {nil 1} {})))))))

(deftest live-dispatch-root-and-failure-do-not-restart
  (let [writer (open-writer) original @#'json/-write calls (atom 0)
        change (Callback. (fn [out _]
                            (swap! calls inc)
                            (alter-var-root #'json/-write
                                            (constantly (fn [_ out _] (.append out "42"))))
                            (.append out "0")))]
    (try
      (is (= "{\"a\":0,\"b\":42}" (encode writer (array-map "a" change "b" "after") {})))
      (is (= 1 @calls))
      (finally (alter-var-root #'json/-write (constantly original))))
    (reset! calls 0)
    (let [bad (Callback. (fn [out _] (swap! calls inc) (.append out "7")
                          (throw (ex-info "expected" {}))))]
      (is (= :failed (try (encode writer {"bad" bad} {}) :returned
                         (catch Throwable _ :failed))))
      (is (= 1 @calls)))))

(deftest nested-writes-keep-call-local-sinks-and-flags
  (doseq [shared? [false true]]
    (let [outer (open-writer) inner (if shared? outer (open-writer))
          key "β/\u2028" prefixes (atom []) calls (atom 0)
          nested-value {key "inner"}
          nested-options {:escape-unicode false :escape-slash false
                          :escape-js-separators false}
          expected-inner (portable nested-value nested-options)
          callback (Callback.
                    (fn [out _]
                      (swap! calls inc)
                      (swap! prefixes conj (.toString out))
                      (.append out (encode inner nested-value nested-options))))
          expected (str "{" (portable key {}) ":" expected-inner
                        ",\"after\":" (portable {key "outer"} {}) "}")]
      ;; Inner cache insertion uses different flags for the same key. The outer
      ;; call must resume with its own flags and real sink, not the inner call's.
      (dotimes [_ 3]
        (is (= expected (encode outer (array-map key callback "after" {key "outer"}) {}))))
      (is (= 3 @calls))
      (is (= (repeat 3 (str "{" (portable key {}) ":")) @prefixes)))))

(deftest failed-payload-does-not-contaminate-next-call
  (let [writer (open-writer) calls (atom 0)
        bad (Callback. (fn [out _] (swap! calls inc) (.append out "7")
                         (throw (ex-info "expected" {}))))]
    (is (= :failed (try (encode writer (array-map "warm" 1 "bad" bad) {})
                       :returned (catch Throwable _ :failed))))
    (is (= 1 @calls))
    (doseq [options [{} {:escape-unicode false} {}]]
      (is (= (portable {"warm" "new-value" "β/" nil} options)
             (encode writer {"warm" "new-value" "β/" nil} options))))))

(deftest independent-payloads-overlap-without-binding-or-sink-leaks
  (let [release (promise) ready [(promise) (promise)]
        writers [(open-writer) (open-writer)]
        options [{} {:escape-unicode false :escape-slash false}]
        workers (mapv
                 (fn [index]
                   (fibers/spawn
                     (fn []
                       (let [writer (nth writers index) opts (nth options index)
                             key "shared-β/"
                             callback (Callback. (fn [out _]
                                                   (deliver (nth ready index) :ready)
                                                   (assert (= :release (deref release 5000 :timeout)))
                                                   (.append out "7")))
                             first-output (encode writer {key callback} opts)
                             first-expected (portable {key 7} opts)]
                         (and (= first-expected first-output)
                              (every? true?
                                (for [i (range 256)]
                                  (let [value (array-map key i "id" index)]
                                    (fibers/yield)
                                    (= (portable value opts) (encode writer value opts))))))))))
                 [0 1])]
    ;; Explicit readiness proves both calls are live before either may return;
    ;; release in finally so a failed readiness assertion cannot strand them.
    (try
      (doseq [signal ready] (is (= :ready (deref signal 5000 :timeout))))
      (finally (deliver release :release)))
    (doseq [worker workers] (is (true? (fibers/join worker 10000 :timeout))))))

(defn -main [& _]
  (let [result (test/run-tests 'clojure.data.json-payload-key-cache-test)]
    (when (pos? (+ (:fail result) (:error result)))
      (throw (ex-info "Payload key-cache tests failed" result)))))

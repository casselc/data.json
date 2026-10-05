(ns clojure.data.json-payload-key-cache-test
  (:require [clojure.data.json :as json]
            [clojure.data.json.jolt-native :as native]
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

(defn -main [& _]
  (let [result (test/run-tests 'clojure.data.json-payload-key-cache-test)]
    (when (pos? (+ (:fail result) (:error result)))
      (throw (ex-info "Payload key-cache tests failed" result)))))

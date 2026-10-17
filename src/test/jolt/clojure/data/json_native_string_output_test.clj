(ns clojure.data.json-native-string-output-test
  (:require [clojure.data.json :as json]
            [clojure.data.json.jolt-native :as native]
            [clojure.test :refer [deftest is]]))

;; This focused namespace runs in a fresh process. Initialize the optional
;; factory before any test ordering can turn "preinstalled" into "later".
(def ^:private preinstalled-output
  (with-redefs [json/write-str (fn [_] "before-first-load")]
    (native/write-str [])))

(deftest string-output-keeps-exact-default-wire-and-independent-results
  (doseq [value [[] (with-meta [] {:source :test}) [1] '() {} nil
                 ["quote\"slash\\漢字😀" 1 true nil] {"a" [1 2]}]]
    (is (= (json/write-str value) (native/write-str value))))
  (let [first (native/write-str [])]
    (is (= "[1]" (native/write-str [1])))
    (is (= "[]" first))))

(deftest preinstalled-and-later-public-writer-replacements-remain-live
  (is (= "before-first-load" preinstalled-output))
  (with-redefs [json/write-str (fn [_] "before-init")]
    (is (= "before-init" (native/write-str []))))
  (is (= "[]" (native/write-str [])))
  (with-redefs [json/write-str (fn [_] "later")]
    (is (= "later" (native/write-str [])))))

(deftest changed-options-dispatcher-and-native-hook-use-the-current-public-path
  (with-redefs [json/default-write-options
                (assoc json/default-write-options :indent true)]
    (is (= (json/write-str []) (native/write-str []))))
  (with-redefs [json/-write (fn [_ out _] (.write out "dispatcher"))]
    (is (= "dispatcher" (native/write-str []))))
  (let [calls (atom 0)]
    (binding [json/*experimental-native-writer*
              (fn [_ out _ _] (swap! calls inc) (.write out "native-hook"))]
      (is (= "native-hook" (native/write-str [])))
      (is (= 1 @calls)))))

(deftest custom-vector-writer-gets-one-fresh-real-sink-and-no-replayed-call
  (let [original @#'json/write-array calls (atom 0) sinks (atom [])]
    (try
      (extend java.util.Collection json/JSONWriter
              {:-write (fn [_ out _]
                         (swap! calls inc)
                         (is (= "" (.toString out)))
                         (swap! sinks conj out)
                         (.append (.getBuffer out) "[7]"))})
      (is (= "[7]" (native/write-str [])))
      (is (= 1 @calls))
      (is (= "[7]" (native/write-str [])))
      (is (= 2 @calls))
      (is (not (identical? (first @sinks) (second @sinks))))
      (finally (extend java.util.Collection json/JSONWriter {:-write original})))))

(deftest selected-custom-writer-error-is-not-retried
  (let [original @#'json/write-array calls (atom 0)
        failure (ex-info "synthetic writer error" {}) caught (atom nil)]
    (try
      (extend java.util.Collection json/JSONWriter
              {:-write (fn [_ _ _] (swap! calls inc) (throw failure))})
      (try (native/write-str []) (catch Throwable e (reset! caught e)))
      (is (identical? failure @caught))
      (is (= 1 @calls))
      (finally (extend java.util.Collection json/JSONWriter {:-write original})))))

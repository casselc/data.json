(ns clojure.data.json-perf-test
  (:require [cheshire.core :as cheshire]
            [clj-async-profiler.core :as prof]
            [clojure.data.json :as json]
            [clojure.java.shell :as shell]
            [criterium.core :refer :all]
            [jsonista.core :as jsonista]
            [clojure.string :as str])
  (:import [com.jsoniter JsonIterator]
           [java.lang.management ManagementFactory]
           [java.security MessageDigest]))

(defmacro profiling [times & body]
  `(try
     (prof/start {})
     (dotimes [_# ~times]
       ~@body)
     (finally
       (prof/stop {:transform (fn [s#]
                                (if (or (str/index-of s# "err_codes_unix")
                                        (str/index-of s# "["))
                                  nil
                                  (-> s#
                                      (str/replace #"^.+/.*read-profiling.+?;" "START;")
                                      (str/replace #"^.*nextToken.*" "jackson-next-token")
                                      (str/replace #"^.*getText.*" "jackson-get-text")
                                      (str/replace #"^.*next.token.*" "data-json-next-token")
                                      (str/replace #"^.*read.quoted.string.*" "data-json-read_quoted_string")
                                      (str/replace #".*assoc.*" "ASSOC;")
                                      (str/replace #".*Map.*" "ASSOC;"))))}))))

(defn json-data [size]
  (slurp (str "dev-resources/json" size ".json")))

(defn- percentile [ordered-samples proportion]
  (nth ordered-samples
       (dec (long (Math/ceil (* proportion (count ordered-samples)))))))

(defn- utf8-size [^String value]
  (alength (.getBytes value "UTF-8")))

(defn- sha-256 [^String value]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes value "UTF-8"))]
    (apply str (map #(format "%02x" (bit-and 0xff %)) digest))))

(defn- sample-summary [samples]
  (let [ordered (vec (sort samples))]
    {:min (first ordered)
     :p50 (percentile ordered 0.50)
     :max (peek ordered)}))

(defn- bench-string-encoding
  [label input expected-output warmups iterations trial]
  (dotimes [_ warmups]
    (json/write-str input))
  (let [expected-code-units (.length ^String expected-output)
        expected-hash (.hashCode ^String expected-output)
        input-bytes (utf8-size input)
        output-bytes (utf8-size expected-output)
        wall-start (System/nanoTime)
        {:keys [samples blackhole]}
        (loop [remaining iterations, timings [], blackhole (long 0)]
                  (if (zero? remaining)
                    {:samples timings :blackhole blackhole}
                    (let [started (System/nanoTime)
                          encoded (json/write-str input)
                          elapsed (- (System/nanoTime) started)
                          actual-code-units (.length ^String encoded)
                          actual-hash (.hashCode ^String encoded)]
                      (when-not (and (= expected-code-units actual-code-units)
                                     (= expected-hash actual-hash)
                                     (= expected-output encoded))
                        (throw (ex-info "String benchmark output changed"
                                        {:scenario label :trial trial
                                         :expected-code-units expected-code-units
                                         :actual-code-units actual-code-units
                                         :expected-hash expected-hash
                                         :actual-hash actual-hash})))
                      (recur (dec remaining)
                             (conj timings elapsed)
                             (unchecked-add blackhole
                                            (long (+ actual-code-units
                                                     actual-hash)))))))
        wall-total (- (System/nanoTime) wall-start)
        sample-total (reduce + samples)
        ordered (vec (sort samples))
        seconds (/ (double sample-total) 1000000000.0)
        mebibytes 1048576.0]
    {:scenario label
     :trial trial
     :input-code-units (.length ^String input)
     :input-utf8-bytes input-bytes
     :output-code-units expected-code-units
     :output-utf8-bytes output-bytes
     :warmups warmups
     :iterations iterations
     :p50-ns (percentile ordered 0.50)
     :p95-ns (percentile ordered 0.95)
     :p99-ns (percentile ordered 0.99)
     :max-ns (peek ordered)
     :sample-total-ns sample-total
     :wall-total-ns wall-total
     :input-code-units-per-second
     (long (/ (* (.length ^String input) iterations) seconds))
     :input-mib-per-second (/ (* input-bytes iterations) mebibytes seconds)
     :output-mib-per-second (/ (* output-bytes iterations) mebibytes seconds)
     :blackhole blackhole}))

(defn- benchmark-scenario [label input warmups iterations trials]
  (let [expected-output (json/write-str input)
        _calibration (bench-string-encoding label input expected-output
                                            warmups iterations 0)
        results (mapv #(bench-string-encoding label input expected-output
                                              warmups iterations %)
                      (range 1 (inc trials)))]
    {:scenario label
     :discarded-calibration-trials 1
     :expected-output {:code-units (.length ^String expected-output)
                       :utf8-bytes (utf8-size expected-output)
                       :sha-256 (sha-256 expected-output)}
     :trials results
     :trial-summary
     {:p50-ns (sample-summary (mapv :p50-ns results))
      :sample-total-ns (sample-summary (mapv :sample-total-ns results))
      :input-mib-per-second (sample-summary (mapv :input-mib-per-second results))
      :output-mib-per-second (sample-summary
                              (mapv :output-mib-per-second results))}}))

(defn- git-output [& args]
  (let [{:keys [exit out]} (apply shell/sh "git" args)]
    (when (zero? exit) (str/trim out))))

(def ^:private safe-jvm-argument-prefixes
  ["-Xms" "-Xmx" "-XX:" "-Dfile.encoding=" "-Djava.io.tmpdir="])

(defn- safe-jvm-arguments []
  (filterv (fn [argument]
             (some #(str/starts-with? argument %)
                   safe-jvm-argument-prefixes))
           (.getInputArguments (ManagementFactory/getRuntimeMXBean))))

(defn- runtime-metadata []
  (let [status (git-output "status" "--porcelain")]
    {:clojure-version (clojure-version)
     :java-version (System/getProperty "java.version")
     :java-vm-name (System/getProperty "java.vm.name")
     :java-vm-version (System/getProperty "java.vm.version")
     :os-name (System/getProperty "os.name")
     :os-version (System/getProperty "os.version")
     :os-arch (System/getProperty "os.arch")
     :available-processors (.availableProcessors (Runtime/getRuntime))
     :maximum-heap-bytes (.maxMemory (Runtime/getRuntime))
     :safe-java-input-arguments (safe-jvm-arguments)
     :system-load-average
     (.getSystemLoadAverage (ManagementFactory/getOperatingSystemMXBean))
     :proc-loadavg (try (str/trim (slurp "/proc/loadavg"))
                        (catch Throwable _ nil))
     :git {:commit (git-output "rev-parse" "HEAD")
           :tree (git-output "rev-parse" "HEAD^{tree}")
           :branch (git-output "rev-parse" "--abbrev-ref" "HEAD")
           :dirty? (not (str/blank? status))}}))

(defn write-string-runs-bench []
  (let [plain-tail (apply str (repeat 8192 "a"))
        one-escape (str "\"" plain-tail)
        mixed-escapes (apply str
                             (repeat 1024 "segment/with\\escape\"and-tail-"))]
    (prn {:schema-version 2
          :benchmark :write-string-runs
          :runtime (runtime-metadata)
          :results [(benchmark-scenario :one-escape-long-plain-tail
                                        one-escape 20 200 5)
                    (benchmark-scenario :representative-mixed-escapes
                                        mixed-escapes 10 80 5)]})))

(defn do-read-bench [size]
  (let [json (json-data size)]
    (println "Results for"  size "json:")
    (println "data.json:")
    (println (with-out-str (quick-bench (json/read-str json))))
    (println "cheshire:")
    (println (with-out-str (quick-bench (cheshire/parse-string-strict json))))
    (println "jsonista:")
    (println (with-out-str (quick-bench (jsonista/read-value json))))
    (println "jsoniter:")
    (println (with-out-str (quick-bench (.read (JsonIterator/parse ^String json)))))))

(defn do-write-bench [size]
  (let [edn (json/read-str (json-data size))]
    (println "Results for"  size "json:")
    (println "data.json:")
    (println (with-out-str (quick-bench (json/write-str edn))))
    (println "cheshire:")
    (println (with-out-str (quick-bench (cheshire/generate-string edn))))
    (println "jsonista:")
    (println (with-out-str (quick-bench (jsonista/write-value-as-string edn))))))

(defn read-bench-all-sizes []
  (doseq [size ["10b" "100b" "1k" "10k" "100k"]]
    (do-read-bench size)))

(defn write-bench-all-sizes []
  (doseq [size ["10b" "100b" "1k" "10k" "100k"]]
    (do-write-bench size)))

(defn profile-read-all-sizes []
  (doseq [size ["10b" "100b" "1k" "10k" "100k"]]
    (let [json (json-data size)]
      (profiling 10000 (json/read-str json))
      (Thread/sleep 1000))))

(defn profile-write-all-sizes []
  (doseq [size ["10b" "100b" "1k" "10k" "100k"]]
    (let [edn (json/read-str (json-data size))]
      (profiling 10000 (json/write-str edn))
      (Thread/sleep 1000))))

(defn read-bench-data-json []
  (doseq [size ["10b" "100b" "1k" "10k" "100k"]]
    (let [json (json-data size)]
      (quick-bench (json/read-str json)))))

(defn write-bench-data-json []
  (doseq [size ["10b" "100b" "1k" "10k" "100k"]]
    (let [json (json/read-str (json-data size))]
      (quick-bench (json/write-str json)))))

(defn write-profiling []
  (doseq [size ["10b" "100b" "1k" "10k" "100k"]]
    (let [json (json/read-str (json-data size))]
      (profiling 10000 (json/write-str json)))))

(defn read-profiling []
  (doseq [size ["10b" "100b" "1k" "10k" "100k"]]
    (let [json (json-data size)]
      (profiling 10000 (json/read-str json)))))

(defn cheshire-read-profiling []
  (doseq [size ["10b" "100b" "1k" "10k" "100k"]]
    (let [json (json-data size)]
      (profiling 10000 (cheshire/parse-string-strict json)))))

(defn jsonista-read-profiling []
  (doseq [size ["10b" "100b" "1k" "10k" "100k"]]
    (let [json (json-data size)]
      (profiling 10000 (jsonista/read-value json)))))


(defn benchit [data]
  (let [json (json/write-str data)]
    (quick-bench (json/read-str json))
    (quick-bench (cheshire/parse-string-strict json))))

;; Used to generate 1000-numbers.json
(defn gen-int []
  (str (rand-int 1000)))

(defn gen-frac []
  (str "." (rand-int 1000)))

(defn gen-exp []
  (str (rand-nth ["e" "E"]) (rand-nth ["" "-" "+"]) (rand-int 5)))

(defn generate-random-json-number []
  (case (rand-int 8)
    ; int
    0 (gen-int)
    1 (str "-" (gen-int))
    ; frac
    2 (str (gen-int) (gen-frac))
    3 (str "-" (gen-int) (gen-frac))
    ; exp
    4 (str (gen-int) (gen-exp))
    5 (str "-" (gen-int) (gen-exp))
    ; combo
    6 (str (gen-int) (gen-frac) (gen-exp))
    7 (str "-" (gen-int) (gen-frac) (gen-exp))))

(defn generate-random-json-numbers [n]
  ;; used to generate dev-resources/1000-numbers.json
  (->> (for [x (range n)]
         (generate-random-json-number))
       (str/join ", ")
       (#(str "[" % "]"))))

(defn read-bench-all-sizes-numbers []
  (let [json (slurp "dev-resources/1000-numbers.json")]
    (println "data.json:")
    (println (with-out-str (quick-bench (json/read-str json))))
    (println "cheshire:")
    (println (with-out-str (quick-bench (cheshire/parse-string-strict json))))
    (println "jsonista:")
    (println (with-out-str (quick-bench (jsonista/read-value json))))
    (println "jsoniter:")
    (println (with-out-str (quick-bench (.read (JsonIterator/parse ^String json)))))))

(defn read-bench-nested-arrays []
  (let [json (slurp "dev-resources/nested-arrays.json")]
    (println "Results for nested arrays:")
    (println "data.json:")
    (println (with-out-str (quick-bench (json/read-str json))))
    (println "cheshire:")
    (println (with-out-str (quick-bench (cheshire/parse-string-strict json))))
    (println "jsonista:")
    (println (with-out-str (quick-bench (jsonista/read-value json))))
    (println "jsoniter:")
    (println (with-out-str (quick-bench (.read (JsonIterator/parse ^String json)))))))

(defn -main [& [benchmark]]
  (case benchmark
    "string-runs" (write-string-runs-bench)
    (throw (ex-info "Unknown benchmark"
                    {:benchmark benchmark
                     :available ["string-runs"]}))))

;; Copyright (c) Stuart Sierra, 2012. All rights reserved.  The use
;; and distribution terms for this software are covered by the Eclipse
;; Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this
;; distribution.  By using this software in any fashion, you are
;; agreeing to be bound by the terms of this license.  You must not
;; remove this notice, or any other, from this software.

(ns ^{:author "Stuart Sierra"
      :doc "JavaScript Object Notation (JSON) parser/generator.
  See http://www.json.org/"}
  clojure.data.json
  (:refer-clojure :exclude (read))
  (:require [clojure.pprint :as pprint])
  (:import (java.io PrintWriter PushbackReader StringWriter
                    Writer StringReader EOFException)))

;; CUSTOM PUSHBACK READER

(set! *warn-on-reflection* true)

(definterface InternalPBR
  (^int readChar [])
  (^long readChars [^chars buffer ^long start ^long bufflen])
  (^void unreadChar [^int c])
  (^void unreadChars [^chars buffer ^int off ^int bufflen])
  (^String sourceString [])
  (^long position [])
  (^void setPosition [^long position])
  (^java.io.Reader toReader []))

(deftype ReaderPBR [^PushbackReader rdr]
  InternalPBR
  (readChar [_]
    (.read rdr))
  (readChars [_  buffer start bufflen]
    (.read rdr ^chars buffer start bufflen))
  (unreadChar [_ c]
    ;; ASSERT: c should never be -1 (EOF)
    (.unread rdr c))
  (unreadChars [_ buffer start bufflen]
    (.unread rdr buffer start bufflen))
  (sourceString [_]
    nil)
  (position [_]
    -1)
  (setPosition [_ _]
    (throw (UnsupportedOperationException.
            "Reader-backed JSON input has no String position")))
  (toReader [_]
    rdr))

(comment
  (compile 'clojure.data.json)
  )

(deftype StringPBR [^String s ^:unsynchronized-mutable ^long pos ^long len]
  InternalPBR
  (readChar [_]
    (if (< pos len)
      (let [p pos]
        (set! pos (unchecked-inc pos))
        (let [c (int (.charAt s p))]
          c))
      (let [i (int -1)]
        i)))
  (readChars [_  buffer start bufflen]
    (let [remaining (- len pos)
          n (Math/min remaining bufflen)]
      (when (pos? n)
        (let [p pos
              end (+ p n)]
          (set! pos end)
          (.getChars ^String s p end ^chars buffer start)))
      (if (pos? n) n -1)))
  (unreadChar [_ _c]
    ;; ASSERT: c should never be -1 (EOF)
    (set! pos (unchecked-dec pos))
    nil)
  (unreadChars [_ _buffer _start bufflen]
    (set! pos (unchecked-subtract pos bufflen))
    nil)
  (sourceString [_]
    s)
  (position [_]
    pos)
  (setPosition [_ position]
    (set! pos position)
    nil)
  (toReader [_]
    (StringReader. (.substring s pos len))))

(defn- pushback-pbr
  [^PushbackReader r]
  (->ReaderPBR r))

(defn- string-pbr
  [^String s]
  (->StringPBR s 0 (.length s)))

;;; JSON READER

(set! *warn-on-reflection* true)

(defn- default-write-key-fn
  [x]
  (cond (instance? clojure.lang.Named x)
        (name x)
        (nil? x)
        (throw (Exception. "JSON object properties may not be nil"))
        :else (str x)))

(defn- default-value-fn [k v] v)

(declare -read)

(defmacro ^:private codepoint [c]
  (int c))

;; JVM strings are indexed by UTF-16 code unit; Jolt strings are indexed by
;; Unicode scalar value. Detect that semantic difference once so escaped and
;; raw supplementary characters can preserve each host's string contract.
(def ^:private scalar-indexed-strings?
  (= 1 (.length "😃")))

(defn- codepoint-clause [[test result]]
  (cond (list? test)
        [(map int test) result]
        (= test :whitespace)
        ['(9 10 13 32) result]
        (= test :js-separators)
        ['(16r2028 16r2029) result]
        :else
        [(int test) result]))

(defmacro ^:private codepoint-case [e & clauses]
  `(case ~e
     ~@(mapcat codepoint-clause (partition 2 clauses))
     ~@(when (odd? (count clauses))
         [(last clauses)])))

(defn- read-hex-code-unit [^InternalPBR stream]
  ;; Expects to be called with the head of the stream AFTER the
  ;; initial "\u".  Reads the next four characters from the stream.
  (let [a (.readChar stream)
        b (.readChar stream)
        c (.readChar stream)
        d (.readChar stream)]
    (when (or (neg? a) (neg? b) (neg? c) (neg? d))
      (throw (EOFException.
              "JSON error (end-of-file inside Unicode character escape)")))
    (let [s (str (char a) (char b) (char c) (char d))]
      (Integer/parseInt s 16))))

(defn- high-surrogate? [cp]
  (<= 0xD800 cp 0xDBFF))

(defn- low-surrogate? [cp]
  (<= 0xDC00 cp 0xDFFF))

(defn- codepoint-string [cp]
  (String. (Character/toChars cp)))

(defn- read-unicode-escape [^InternalPBR stream]
  (let [head (read-hex-code-unit stream)]
    (if-not scalar-indexed-strings?
      ;; Preserve data.json's JVM behavior exactly: each \uXXXX denotes one
      ;; UTF-16 code unit, including an isolated surrogate.
      (codepoint-string head)
      (cond
        (high-surrogate? head)
        (let [slash (.readChar stream)
              u (.readChar stream)]
          (when (or (neg? slash) (neg? u))
            (throw (EOFException.
                    "JSON error (end-of-file inside Unicode surrogate pair)")))
          (when (or (not= slash (int \\)) (not= u (int \u)))
            (throw (Exception.
                    "JSON error (high surrogate not followed by a low-surrogate escape)")))
          (let [tail (read-hex-code-unit stream)]
            (when-not (low-surrogate? tail)
              (throw (Exception.
                      "JSON error (high surrogate not followed by a low surrogate)")))
            (codepoint-string
              (+ 0x10000 (* (- head 0xD800) 0x400) (- tail 0xDC00)))))

        (low-surrogate? head)
        (throw (Exception. "JSON error (unpaired low surrogate)"))

        :else
        (codepoint-string head)))))

(defn- read-escaped-char [^InternalPBR stream]
  ;; Expects to be called with the head of the stream AFTER the
  ;; initial backslash.
  (let [c (.readChar stream)]
    (when (neg? c)
      (throw (EOFException. "JSON error (end-of-file inside escaped char)")))
    (codepoint-case c
      (\" \\ \/) (str (char c))
      \b (str \backspace)
      \f (str \formfeed)
      \n (str \newline)
      \r (str \return)
      \t (str \tab)
      \u (read-unicode-escape stream))))

(defn- slow-read-string [^InternalPBR stream ^String already-read]
  (let [buffer (StringBuilder. already-read)]
    (loop []
      (let [c (.readChar stream)]
        (when (neg? c)
          (throw (EOFException. "JSON error (end-of-file inside string)")))
        (codepoint-case c
          \" (str buffer)
          \\ (do (.append buffer (read-escaped-char stream))
                 (recur))
          (do (if (<= c 0xFFFF)
                (.append buffer (char c))
                (.append buffer (codepoint-string c)))
              (recur)))))))

(defn- next-string-special [^String s start]
  (let [quote (.indexOf s (int \" ) (int start))
        escape (.indexOf s (int \\) (int start))]
    (cond
      (neg? quote) escape
      (neg? escape) quote
      :else (min quote escape))))

(defn- simple-escaped-string [^long c]
  ;; Keep the String-backed reader's common escapes on its local cursor.  The
  ;; returned constants avoid constructing one single-character String per
  ;; escape; Unicode and invalid/EOF inputs deliberately remain on the shared
  ;; decoder below so their established errors and host string semantics stay
  ;; in one place.
  (codepoint-case c
    \" "\""
    \\ "\\"
    \/ "/"
    \b "\b"
    \f "\f"
    \n "\n"
    \r "\r"
    \t "\t"
    nil))

(defn- append-nonempty-string-run
  [^StringBuilder output ^String s ^long start ^long end]
  ;; Jolt has a direct StringBuilder/String append path.  In particular, a
  ;; leading or adjacent escape has an empty run before it; preserve that no-op
  ;; without constructing an empty substring or resolving a range append.
  (if (< start end)
    (.append output (.substring s (int start) (int end)))
    output))

(defn- read-quoted-string-from-string [^StringPBR stream]
  ;; read-str already owns an immutable String. Let the host String locate the
  ;; next quote or escape and append whole ordinary runs, rather than crossing
  ;; the host boundary once per character after the initial 64-character block.
  (let [s ^String (.sourceString stream)
        len (.length s)]
    (loop [run-start (.position stream), output nil]
      (let [special-index (next-string-special s run-start)]
        (if (neg? special-index)
          (do
            (.setPosition stream len)
            (throw (EOFException. "JSON error (end-of-file inside string)")))
          (let [special (int (.charAt s special-index))]
            (if (= special (int \"))
              (do
                ;; The public reader position is observable through remaining
                ;; data and through failures.  Synchronize it before returning,
                ;; while avoiding two mutable-reader calls per simple escape.
                (.setPosition stream (unchecked-inc special-index))
                (if output
                  (do
                    (append-nonempty-string-run output s
                                                run-start special-index)
                    (str output))
                  (.substring s (int run-start) (int special-index))))
              (let [output (or output (StringBuilder.))]
                (append-nonempty-string-run output s run-start special-index)
                (let [escape-index (long (unchecked-inc special-index))
                      escaped
                      (when (< escape-index len)
                        (simple-escaped-string
                         (int (.charAt s escape-index))))]
                  (if escaped
                    (do
                      (.append ^StringBuilder output escaped)
                      (recur (unchecked-inc escape-index) output))
                    (do
                      ;; Unicode, invalid escapes, and EOF retain the original
                      ;; decoder and its exact position/error behavior.
                      (.setPosition stream escape-index)
                      (.append ^StringBuilder output
                               (read-escaped-char stream))
                      (recur (.position stream) output))))))))))))

(defn- read-quoted-string [^InternalPBR stream]
  ;; Expects to be called with the head of the stream AFTER the
  ;; opening quotation mark.
  (if (instance? StringPBR stream)
    (read-quoted-string-from-string stream)
    (let [buffer ^chars (char-array 64)
          read (.readChars stream buffer 0 64)
          end-index (unchecked-dec-int read)]
      (when (neg? read)
        (throw (EOFException. "JSON error (end-of-file inside string)")))
      (loop [i (int 0)]
        (let [c (int (aget buffer i))]
          (codepoint-case c
            \" (let [off (unchecked-inc-int i)
                     len (unchecked-subtract-int read off)]
                 (.unreadChars stream buffer off len)
                 (String. buffer 0 i))
            \\ (let [off i
                     len (unchecked-subtract-int read off)]
                 (.unreadChars stream buffer off len)
                 (slow-read-string stream (String. buffer 0 i)))
            (if (= i end-index)
              (do (.unreadChar stream c)
                  (slow-read-string stream (String. buffer 0 i)))
              (recur (unchecked-inc-int i)))))))))

(defn- read-integer [^String string]
  (if (< (count string) 18)  ; definitely fits in a Long
    (Long/valueOf string)
    (or (try (Long/valueOf string)
             (catch NumberFormatException e nil))
        (bigint string))))

(defn- read-decimal [^String string bigdec?]
  (if bigdec?
    (bigdec string)
    (Double/valueOf string)))

(defn- read-number [^InternalPBR stream bigdec?]
  (let [buffer (StringBuilder.)
        decimal? (loop [stage :minus]
                   (let [c (.readChar stream)]
                     (case stage
                       :minus
                       (codepoint-case c
                         \-
                         (do (.append buffer (char c))
                             (recur :int-zero))
                         \0
                         (do (.append buffer (char c))
                             (recur :frac-point))
                         (\1 \2 \3 \4 \5 \6 \7 \8 \9)
                         (do (.append buffer (char c))
                             (recur :int-digit))
                         (throw (Exception. "JSON error (invalid number literal)")))
                       ;; Number must either be a single 0 or 1-9 followed by 0-9
                       :int-zero
                       (codepoint-case c
                         \0
                         (do (.append buffer (char c))
                             (recur :frac-point))
                         (\1 \2 \3 \4 \5 \6 \7 \8 \9)
                         (do (.append buffer (char c))
                             (recur :int-digit))
                         (throw (Exception. "JSON error (invalid number literal)")))
                       ;; at this point, there is at least one digit
                       :int-digit
                       (codepoint-case c
                         (\0 \1 \2 \3 \4 \5 \6 \7 \8 \9)
                         (do (.append buffer (char c))
                             (recur :int-digit))
                         \.
                         (do (.append buffer (char c))
                             (recur :frac-first))
                         (\e \E)
                         (do (.append buffer (char c))
                             (recur :exp-symbol))
                         ;; early exit
                         :whitespace
                         (do (.unreadChar stream c)
                             false)
                         (\, \] \})
                         (do (.unreadChar stream c)
                             false)
                         -1
                         false
                         (throw (Exception. "JSON error (invalid number literal)")))
                       ;; previous character is a "0"
                       :frac-point
                       (codepoint-case c
                         \.
                         (do (.append buffer (char c))
                             (recur :frac-first))
                         (\e \E)
                         (do (.append buffer (char c))
                             (recur :exp-symbol))
                         ;; early exit
                         :whitespace
                         (do (.unreadChar stream c)
                             false)
                         (\, \] \})
                         (do (.unreadChar stream c)
                             false)
                         -1
                         false
                         ;; Disallow zero-padded numbers or invalid characters
                         (throw (Exception. "JSON error (invalid number literal)")))
                       ;; previous character is a "."
                       :frac-first
                       (codepoint-case c
                         (\0 \1 \2 \3 \4 \5 \6 \7 \8 \9)
                         (do (.append buffer (char c))
                             (recur :frac-digit))
                         (throw (Exception. "JSON error (invalid number literal)")))
                       ;; any number of following digits
                       :frac-digit
                       (codepoint-case c
                         (\0 \1 \2 \3 \4 \5 \6 \7 \8 \9)
                         (do (.append buffer (char c))
                             (recur :frac-digit))
                         (\e \E)
                         (do (.append buffer (char c))
                             (recur :exp-symbol))
                         ;; early exit
                         :whitespace
                         (do (.unreadChar stream c)
                             true)
                         (\, \] \})
                         (do (.unreadChar stream c)
                             true)
                         -1
                         true
                         (throw (Exception. "JSON error (invalid number literal)")))
                       ;; previous character is a "e" or "E"
                       :exp-symbol
                       (codepoint-case c
                         (\- \+)
                         (do (.append buffer (char c))
                             (recur :exp-first))
                         (\0 \1 \2 \3 \4 \5 \6 \7 \8 \9)
                         (do (.append buffer (char c))
                             (recur :exp-digit)))
                       ;; previous character is a "-" or "+"
                       ;; must have at least one digit
                       :exp-first
                       (codepoint-case c
                         (\0 \1 \2 \3 \4 \5 \6 \7 \8 \9)
                         (do (.append buffer (char c))
                             (recur :exp-digit))
                         (throw (Exception. "JSON error (invalid number literal)")))
                       ;; any number of following digits
                       :exp-digit
                       (codepoint-case c
                         (\0 \1 \2 \3 \4 \5 \6 \7 \8 \9)
                         (do (.append buffer (char c))
                             (recur :exp-digit))
                         :whitespace
                         (do (.unreadChar stream c)
                             true)
                         (\, \] \})
                         (do (.unreadChar stream c)
                             true)
                         -1
                         true
                         (throw (Exception. "JSON error (invalid number literal)"))))))]
    (if decimal?
      (read-decimal (str buffer) bigdec?)
      (read-integer (str buffer)))))

(defn- next-token [^InternalPBR stream]
  (loop [c (.readChar stream)]
    (if (< 32 c)
      (int c)
      (codepoint-case (int c)
        :whitespace (recur (.readChar stream))
        c))))

(defn invalid-array-exception []
  (Exception. "JSON error (invalid array)"))

(defn- eof-array-exception []
  (EOFException. "JSON error (EOF in array)"))

(defn- read-array* [^InternalPBR stream options]
  ;; Handles all array values after the first.
  (loop [result (transient [])]
    (let [r (conj! result (-read stream true nil options))]
      (codepoint-case (int (next-token stream))
        \] (persistent! r)
        \, (recur r)
        -1 (throw (eof-array-exception))
        (throw (invalid-array-exception))))))

(defn- read-array [^InternalPBR stream options]
  ;; Expects to be called with the head of the stream AFTER the
  ;; opening bracket.
  ;; Only handles array value.
  (let [c (int (next-token stream))]
    (codepoint-case c
      \] []
      \, (throw (invalid-array-exception))
      -1 (throw (eof-array-exception))
      (do (.unreadChar stream c)
          (read-array* stream options)))))

(defn- object-colon-exception []
  (Exception. "JSON error (missing `:` in object)"))

(defn- eof-object-exception []
  (EOFException. "JSON error (EOF in object)"))

(defn- invalid-key-exception [c]
  (if (= c -1)
    (throw (eof-object-exception))
    (throw (Exception. (str "JSON error (non-string key in object), found `" (char c) "`, expected `\"`")))))

(comment
  (compile 'clojure.data.json)
  )

(defn- read-key [^InternalPBR stream]
  (let [c (int (next-token stream))]
    (if (= c (codepoint \"))
      (let [key (read-quoted-string stream)]
        (if (= (codepoint \:) (int (next-token stream)))
          key
          (throw (object-colon-exception))))
      (if (= c (codepoint \}))
        nil
        (invalid-key-exception c)))))

(defn- read-object [^InternalPBR stream options]
  ;; Expects to be called with the head of the stream AFTER the
  ;; opening bracket.
  (let [key-fn (get options :key-fn)
        value-fn (get options :value-fn)]
    (loop [result (transient {})]
      (if-let [key (read-key stream)]
        (let [key (cond-> key key-fn key-fn)
              value (-read stream true nil options)
              r (if value-fn
                  (let [out-value (value-fn key value)]
                    (if-not (= value-fn out-value)
                      (assoc! result key out-value)
                      result))
                  (assoc! result key value))]
          (codepoint-case (int (next-token stream))
            \, (recur r)
            \} (persistent! r)
            -1 (throw (eof-object-exception))
            (throw (Exception. "JSON error (missing entry in object)"))))
        (let [r (persistent! result)]
          (if (empty? r)
            r
            (throw (Exception. "JSON error empty entry in object is not allowed"))))))))

(defn- -read
  [^InternalPBR stream eof-error? eof-value options]
  (let [c (int (next-token stream))]
    (codepoint-case c
        ;; Read numbers
        (\- \0 \1 \2 \3 \4 \5 \6 \7 \8 \9)
        (do (.unreadChar stream c)
            (read-number stream (:bigdec options)))

        ;; Read strings
        \" (read-quoted-string stream)

        ;; Read null as nil
        \n (if (and (= (codepoint \u) (.readChar stream))
                    (= (codepoint \l) (.readChar stream))
                    (= (codepoint \l) (.readChar stream)))
             nil
             (throw (Exception. "JSON error (expected null)")))

        ;; Read true
        \t (if (and (= (codepoint \r) (.readChar stream))
                    (= (codepoint \u) (.readChar stream))
                    (= (codepoint \e) (.readChar stream)))
             true
             (throw (Exception. "JSON error (expected true)")))

        ;; Read false
        \f (if (and (= (codepoint \a) (.readChar stream))
                    (= (codepoint \l) (.readChar stream))
                    (= (codepoint \s) (.readChar stream))
                    (= (codepoint \e) (.readChar stream)))
             false
             (throw (Exception. "JSON error (expected false)")))

        ;; Read JSON objects
        \{ (read-object stream options)

        ;; Read JSON arrays
        \[ (read-array stream options)

        (if (neg? c) ;; Handle end-of-stream
          (if eof-error?
            (throw (EOFException. "JSON error (end-of-file)"))
            eof-value)
          (throw (Exception.
                  (str "JSON error (unexpected character): " (char c))))))))

(defn- -read1
  [^InternalPBR stream eof-error? eof-value options]
  (let [val (-read stream eof-error? eof-value options)]
    (if-let [extra-data-fn (:extra-data-fn options)]
      (if (or eof-error? (not (identical? eof-value val)))
        (let [c (.readChar stream)]
          (if (neg? c)
            val
            (do
              (.unreadChar stream c)
              (extra-data-fn val (.toReader stream)))))
        val)
      val)))

(defn on-extra-throw
  "Pass as :extra-data-fn to `read` or `read-str` to throw if data is found
  after the first object."
  [val rdr]
  (throw (ex-info "Found extra data after json object" {:val val})))

(defn on-extra-throw-remaining
  "Pass as :extra-data-fn to `read` or `read-str` to throw if data is found
  after the first object and return the remaining data in ex-data :remaining."
  [val rdr]
  (let [remaining (slurp rdr)]
    (throw (ex-info (str "Found extra data after json object: " remaining)
             {:val val, :remaining remaining}))))

(def default-read-options {:bigdec false
                           :key-fn nil
                           :value-fn nil})
(defn read
  "Reads a single item of JSON data from a java.io.Reader.

  If you wish to repeatedly read items from the same reader, you must
  supply a PushbackReader with buffer size >= 64, and reuse it on
  subsequent calls.

  Options are key-value pairs, valid options are:

     :eof-error? boolean

        If true (default) will throw exception if the stream is empty.

     :eof-value Object

        Object to return if the stream is empty and eof-error? is
        false. Default is nil.

     :bigdec boolean

        If true use BigDecimal for decimal numbers instead of Double.
        Default is false.

     :key-fn function

        Single-argument function called on JSON property names; return
        value will replace the property names in the output. Default
        is clojure.core/identity, use clojure.core/keyword to get
        keyword properties.

     :value-fn function

        Function to transform values in maps (\"objects\" in JSON) in
        the output. For each JSON property, value-fn is called with
        two arguments: the property name (transformed by key-fn) and
        the value. The return value of value-fn will replace the value
        in the output. If value-fn returns itself, the property will
        be omitted from the output. The default value-fn returns the
        value unchanged. This option does not apply to non-map
        collections.

     :extra-data-fn function

       If :extra-data-fn is not nil, then the reader will be checked
       for extra data after the read. If found, the extra-data-fn will
       be invoked with the read value and the reader. The result of
       the extra-data-fn will be returned."
  [reader & {:as options}]
  (let [{:keys [eof-error? eof-value]
         :or {eof-error? true}} options
        pbr (pushback-pbr
              (if (instance? PushbackReader reader)
                reader
                (PushbackReader. reader 64)))]
    (->> options
         (merge default-read-options)
         (-read1 pbr eof-error? eof-value))))

(defn read-str
  "Reads one JSON value from input String. Options are the same as for
  read."
  [string & {:as options}]
  (let [{:keys [eof-error? eof-value]
         :or {eof-error? true}} options]
    (->> options
         (merge default-read-options)
         (-read1 (string-pbr string) eof-error? eof-value))))

;;; JSON WRITER


(defprotocol JSONWriter
  (-write [object out options]
    "Print object to Appendable out as JSON"))

(defn- ->hex-string [^Appendable out cp]
  (let [cpl (long cp)]
    (.append out "\\u")
    (cond
      (< cpl 16)
      (.append out "000")
      (< cpl 256)
      (.append out "00")
      (< cpl 4096)
      (.append out "0"))
    (.append out (Integer/toHexString cp))))

(defn- ->unicode-escape [^Appendable out cp]
  (if (<= cp 0xFFFF)
    (->hex-string out cp)
    (let [supplementary (- cp 0x10000)]
      (->hex-string out (+ 0xD800 (quot supplementary 0x400)))
      (->hex-string out (+ 0xDC00 (mod supplementary 0x400))))))

(defn- append-codepoint [^Appendable out cp]
  (if (<= cp 0xFFFF)
    (.append out (char cp))
    (.append out ^String (codepoint-string cp))))

(def ^{:tag "[S"} codepoint-decoder
  (let [shorts (short-array 128)]
    (dotimes [i 128]
      (codepoint-case i
        \" (aset shorts i (short 1))
        \\ (aset shorts i (short 1))
        \/ (aset shorts i (short 2))
        \backspace (aset shorts i (short 3))
        \formfeed  (aset shorts i (short 4))
        \newline   (aset shorts i (short 5))
        \return    (aset shorts i (short 6))
        \tab       (aset shorts i (short 7))
        (if (< i 32)
          (aset shorts i (short 8))
          (aset shorts i (short 0)))))
    shorts))

(defn- slow-write-string [^CharSequence s ^Appendable out options]
  (let [^shorts decoder codepoint-decoder
        slash (get options :escape-slash)
        escape-js-separators (get options :escape-js-separators)
        escape-unicode (get options :escape-unicode)
        l (.length s)]
    (loop [i (long 0), run-start (long 0)]
      (if (< i l)
        (let [head (int (.charAt s i))
              tail (if (and (not scalar-indexed-strings?)
                            (<= 0xD800 head 0xDBFF)
                            (< (inc i) l))
                     (int (.charAt s (inc i)))
                     -1)
              pair? (<= 0xDC00 tail 0xDFFF)
              cp (int (if pair?
                        (+ 0x10000 (* (- head 0xD800) 0x400) (- tail 0xDC00))
                        head))
              decoder-code (int (if (< cp 128) (aget decoder cp) 0))
              escape? (if (< cp 128)
                        (and (not (zero? decoder-code))
                             (not (and (= decoder-code 2) (not slash))))
                        (codepoint-case cp
                          :js-separators escape-js-separators
                          escape-unicode))
              next-i (unchecked-add i (long (if pair? 2 1)))]
          (if escape?
            (do
              (when (< run-start i)
                (.append out s (int run-start) (int i)))
              (if (< cp 128)
                (case decoder-code
                  1 (do (.append out (char (codepoint \\))) (.append out (char cp)))
                  2 (.append out "\\/")
                  3 (.append out "\\b")
                  4 (.append out "\\f")
                  5 (.append out "\\n")
                  6 (.append out "\\r")
                  7 (.append out "\\t")
                  8 (->hex-string out cp))
                (->unicode-escape out cp))
              (recur next-i next-i))
            (recur next-i run-start)))
        (when (< run-start l)
          (.append out s (int run-start) (int l)))))))

(defn- write-string [^CharSequence s ^Appendable out options]
  (let [^shorts decoder codepoint-decoder
        l (.length s)]
    (.append out \")
    (loop [i (long 0)]
      (if (= i l)
        (.append out s)
        (let [cp (int (.charAt s i))]
          (if (and (< cp 128)
                   (zero? (aget decoder cp)))
            (recur (unchecked-inc i))
            (do
              (when (pos? i)
                (.append out s 0 i))
              (slow-write-string (.subSequence s i l) out options))))))
    (.append out \")))

(defn- write-indent [^Appendable out options]
  (let [indent-depth (:indent-depth options)]
    (.append out \newline)
    (loop [i indent-depth]
      (when (pos? i)
        (.append out "  ")
        (recur (dec i))))))

(defn- write-object [m ^Appendable out options]
  (let [key-fn (get options :key-fn)
        value-fn (get options :value-fn)
        default-key? (identical? key-fn default-write-key-fn)
        default-value? (identical? value-fn default-value-fn)
        indent (get options :indent)
        opts (cond-> options
               indent (update :indent-depth inc))]
    (.append out \{)
    (when (and indent (seq m))
      (write-indent out opts))
    (loop [x m, have-printed-kv false]
      (when (seq x)
        (let [[k v] (first x)
              out-key (if (and default-key? (string? k)) k (key-fn k))
              out-value (if default-value? v (value-fn k v))
              nxt (next x)]
          (when-not (string? out-key)
            (throw (Exception. "JSON object keys must be strings")))
          (if-not (= value-fn out-value)
            (do
              (when have-printed-kv
                (.append out \,)
                (when indent
                  (write-indent out opts)))
              (write-string out-key out opts)
              (.append out \:)
              (when indent
                (.append out \space))
              (-write out-value out opts)
              (when (seq nxt)
                (recur nxt true)))
            (when (seq nxt)
              (recur nxt have-printed-kv))))))
    (when (and indent (seq m))
      (write-indent out options)))
  (.append out \}))

(defn- write-array [s ^Appendable out options]
  (let [indent (get options :indent)
        opts (cond-> options
                  indent (update :indent-depth inc))]
    (.append out \[)
    (when (and indent (seq s))
      (write-indent out opts))
    (loop [x s]
      (when (seq x)
        (let [fst (first x)
              nxt (next x)]
          (-write fst out opts)
          (when (seq nxt)
            (.append out \,)
            (when indent
              (write-indent out opts))
            (recur nxt)))))
    (when (and indent (seq s))
      (write-indent out options)))
  (.append out \]))

(defn- write-bignum [x ^Appendable out options]
  (.append out (str x)))

(defn- write-float [^Float x ^Appendable out options]
  (cond (.isInfinite x)
        (throw (Exception. "JSON error: cannot write infinite Float"))
        (.isNaN x)
        (throw (Exception. "JSON error: cannot write Float NaN"))
        :else
        (.append out (str x))))

(defn- write-double [^Double x ^Appendable out options]
  (cond (.isInfinite x)
        (throw (Exception. "JSON error: cannot write infinite Double"))
        (.isNaN x)
        (throw (Exception. "JSON error: cannot write Double NaN"))
        :else
        (.append out (str x))))

(defn- write-plain [x ^Appendable out options]
  (.append out (str x)))

(defn- write-uuid [^java.util.UUID x ^Appendable out options]
  (.append out \")
  (.append out (.toString x))
  (.append out \"))

(defn- write-instant [^java.time.Instant x ^Appendable out options]
  (let [formatter ^java.time.format.DateTimeFormatter (:date-formatter options)]
    (.append out \")
    (.append out (.format formatter x))
    (.append out \")))

(defn- write-date [^java.util.Date x ^Appendable out options]
  (write-instant (.toInstant x) out options))

(defn- default-sql-date->instant-fn [^java.sql.Date d]
  (.toInstant (.atStartOfDay (.toLocalDate d) (java.time.ZoneId/systemDefault))))

(defn- write-sql-date [^java.sql.Date x ^Appendable out options]
  (let [->instant (:sql-date-converter options)]
    (write-instant (->instant x) out options)))

(defn- write-null [x ^Appendable out options]
  (.append out "null"))

(defn- write-named [x out options]
  (write-string (name x) out options))

(defn- write-generic [x out options]
  (if (.isArray (class x))
    (-write (seq x) out options)
    ((:default-write-fn options) x out options)))

(defn- write-ratio [x out options]
  (-write (double x) out options))

;; nil, true, false
(extend nil                    JSONWriter {:-write write-null})
(extend java.lang.Boolean      JSONWriter {:-write write-plain})

;; Numbers
(extend java.lang.Byte         JSONWriter {:-write write-plain})
(extend java.lang.Short        JSONWriter {:-write write-plain})
(extend java.lang.Integer      JSONWriter {:-write write-plain})
(extend java.lang.Long         JSONWriter {:-write write-plain})
(extend java.lang.Float        JSONWriter {:-write write-float})
(extend java.lang.Double       JSONWriter {:-write write-double})
(extend clojure.lang.Ratio     JSONWriter {:-write write-ratio})
(extend java.math.BigInteger   JSONWriter {:-write write-bignum})
(extend java.math.BigDecimal   JSONWriter {:-write write-bignum})
(extend java.util.concurrent.atomic.AtomicInteger JSONWriter {:-write write-plain})
(extend java.util.concurrent.atomic.AtomicLong    JSONWriter {:-write write-plain})
(extend java.util.UUID         JSONWriter {:-write write-uuid})
(extend java.time.Instant      JSONWriter {:-write write-instant})
(extend java.util.Date         JSONWriter {:-write write-date})
(extend java.sql.Date          JSONWriter {:-write write-sql-date})
(extend clojure.lang.BigInt    JSONWriter {:-write write-bignum})

;; Symbols, Keywords, and Strings
(extend clojure.lang.Named     JSONWriter {:-write write-named})
(extend java.lang.CharSequence JSONWriter {:-write write-string})

;; Collections
(extend java.util.Map          JSONWriter {:-write write-object})
(extend java.util.Collection   JSONWriter {:-write write-array})

;; Maybe a Java array, otherwise fail
(extend java.lang.Object       JSONWriter {:-write write-generic})

(defn- default-write-fn [x out options]
  (throw (Exception. (str "Don't know how to write JSON of " (class x)))))

(def default-write-options {:escape-unicode true
                            :escape-js-separators true
                            :escape-slash true
                            :sql-date-converter default-sql-date->instant-fn
                            :date-formatter java.time.format.DateTimeFormatter/ISO_INSTANT
                            :key-fn default-write-key-fn
                            :value-fn default-value-fn
                            :default-write-fn default-write-fn
                            :indent false
                            :indent-depth 0 ;; internal, to track nesting depth
                            })
(defn write
  "Write JSON-formatted output to a java.io.Writer. Options are
   key-value pairs, valid options are:

    :escape-unicode boolean

       If true (default) non-ASCII characters are escaped as \\uXXXX

    :escape-js-separators boolean

       If true (default) the Unicode characters U+2028 and U+2029 will
       be escaped as \\u2028 and \\u2029 even if :escape-unicode is
       false. (These two characters are valid in pure JSON but are not
       valid in JavaScript strings.)

    :escape-slash boolean

       If true (default) the slash / is escaped as \\/

    :sql-date-converter function

       Single-argument function used to convert a java.sql.Date to
       a java.time.Instant. As java.sql.Date does not have a
       time-component (which is required by java.time.Instant), it needs
       to be computed. The default implementation, `default-sql-date->instant-fn`
       uses
       ```
          (.toInstant (.atStartOfDay (.toLocalDate sql-date) (java.time.ZoneId/systemDefault)))
       ```

    :date-formatter

        A java.time.DateTimeFormatter instance, defaults to DateTimeFormatter/ISO_INSTANT

    :key-fn function

        Single-argument function called on map keys; return value will
        replace the property names in the output. Must return a
        string. Default calls clojure.core/name on symbols and
        keywords and clojure.core/str on everything else.

    :value-fn function

        Function to transform values in maps before writing. For each
        key-value pair in an input map, called with two arguments: the
        key (BEFORE transformation by key-fn) and the value. The
        return value of value-fn will replace the value in the output.
        If the return value is a number, boolean, string, or nil it
        will be included literally in the output. If the return value
        is a non-map collection, it will be processed recursively. If
        the return value is a map, it will be processed recursively,
        calling value-fn again on its key-value pairs. If value-fn
        returns itself, the key-value pair will be omitted from the
        output. This option does not apply to non-map collections.

     :default-write-fn function

        Function to handle types which are unknown to data.json. Defaults
        to a function which throws an exception. Expects to be called with
        three args, the value to be serialized, the output stream, and the
        options map.

    :indent boolean

        If true, indent json while writing (default = false)."
  ([x ^Writer writer]
   ;; This is the overwhelmingly common public call shape.  Keeping the
   ;; immutable defaults as-is avoids constructing an empty option map and
   ;; merging it for every scalar written to an existing Writer.
   (-write x writer default-write-options))
  ([x ^Writer writer & {:as options}]
   (-write x writer (merge default-write-options options))))

(defn write-str
  "Converts x to a JSON-formatted string. Options are the same as
  write."
  (^String [x]
   (let [sw (StringWriter.)]
     (-write x sw default-write-options)
     (.toString sw)))
  (^String [x & {:as options}]
   (let [sw (StringWriter.)]
     (-write x sw (merge default-write-options options))
     (.toString sw))))

;;; JSON PRETTY-PRINTER

;; Based on code by Tom Faulhaber

(defn- pprint-array [s] 
  ((pprint/formatter-out "~<[~;~@{~w~^, ~:_~}~;]~:>") s))

(defn- pprint-object [m options]
  (let [key-fn (:key-fn options)]
    ((pprint/formatter-out "~<{~;~@{~<~w:~_~w~:>~^, ~_~}~;}~:>")
     (for [[k v] m] [(key-fn k) v]))))

(defn- pprint-generic [x options]
  (if (.isArray (class x))
    (pprint-array (seq x))
    ;; pprint proxies Writer, so we can't just wrap it
    (print (with-out-str (-write x (PrintWriter. *out*) options)))))

(defn- pprint-dispatch [x options]
  (cond (nil? x) (print "null")
        (instance? java.util.Map x) (pprint-object x options)
        (instance? java.util.Collection x) (pprint-array x)
        (instance? clojure.lang.ISeq x) (pprint-array x)
        :else (pprint-generic x options)))

(defn pprint
  "Pretty-prints JSON representation of x to *out*. Options are the same
  as for write except :value-fn and :indent, which are not supported."
  [x & {:as options}]
  (let [opts (merge default-write-options options)]
    (pprint/with-pprint-dispatch #(pprint-dispatch % opts)
      (pprint/pprint x))))

;; DEPRECATED APIs from 0.1.x

(defn read-json
  "DEPRECATED; replaced by read-str.

  Reads one JSON value from input String or Reader. If keywordize? is
  true (default), object keys will be converted to keywords. If
  eof-error? is true (default), empty input will throw an
  EOFException; if false EOF will return eof-value."
  ([input]
   (read-json input true true nil))
  ([input keywordize?]
   (read-json input keywordize? true nil))
  ([input keywordize? eof-error? eof-value]
   (let [key-fn (if keywordize? keyword identity)]
     (condp instance? input
       String
       (read-str input
         :key-fn key-fn
         :eof-error? eof-error?
         :eof-value eof-value)
       java.io.Reader
       (read input
         :key-fn key-fn
         :eof-error? eof-error?
         :eof-value eof-value)))))

(defn write-json
  "DEPRECATED; replaced by 'write'.

  Print object to PrintWriter out as JSON"
  [x out escape-unicode?]
  (write x out :escape-unicode escape-unicode?))

(defn json-str
  "DEPRECATED; replaced by 'write-str'.

  Converts x to a JSON-formatted string.

  Valid options are:
    :escape-unicode false
        to turn of \\uXXXX escapes of Unicode characters."
  [x & options]
  (apply write-str x options))

(defn print-json
  "DEPRECATED; replaced by 'write' to *out*.

  Write JSON-formatted output to *out*.

  Valid options are:
    :escape-unicode false
        to turn off \\uXXXX escapes of Unicode characters."
  [x & options]
  (apply write x *out* options))

(defn pprint-json
  "DEPRECATED; replaced by 'pprint'.

  Pretty-prints JSON representation of x to *out*.

  Valid options are:
    :escape-unicode false
        to turn off \\uXXXX escapes of Unicode characters."
  [x & options]
  (apply pprint x options))

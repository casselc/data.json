;; Library-owned, opt-in Jolt encoder. No encoded-key/value cache and no copied
;; collection representation. Every JSON value checks the current protocol Var
;; root and resolves its method afresh; this is deliberately NOT an epoch cache.
;; The caller supplies a real StringWriter. Both native output and custom
;; writers mutate that same sink, so fallback never restarts or repeats effects.

(define djn-method-cell (jolt-var "clojure.data.json" "-write"))
(define djn-hex "0123456789abcdef")
(define djn-escape-keys
  (map (lambda (name) (keyword #f name))
       '("escape-unicode" "escape-slash" "escape-js-separators")))
(define djn-key-fn (keyword #f "key-fn"))
(define djn-value-fn (keyword #f "value-fn"))

(define (djn-u16 cp out)
  (put-string out "\\u")
  (put-char out (string-ref djn-hex (fxand (fxsra cp 12) 15)))
  (put-char out (string-ref djn-hex (fxand (fxsra cp 8) 15)))
  (put-char out (string-ref djn-hex (fxand (fxsra cp 4) 15)))
  (put-char out (string-ref djn-hex (fxand cp 15))))

(define (djn-string value writer flags)
  ;; Build only this scalar's escaped text, then append once to the shared
  ;; StringWriter. A callback can inspect the complete prefix immediately.
  (sb-append! writer
    (call-with-string-output-port
      (lambda (out)
        (let ((size (string-length value))
              (unicode? (vector-ref flags 0))
              (slash? (vector-ref flags 1))
              (js? (vector-ref flags 2)))
          (put-char out #\")
          (let loop ((i 0) (start 0))
            (if (fx=? i size)
                (when (fx<? start size)
                  (put-string out value start (fx- size start)))
                (let* ((c (string-ref value i)) (cp (char->integer c))
                       (escape?
                         (or (fx<? cp 32) (fx=? cp 34) (fx=? cp 92)
                             (and slash? (fx=? cp 47))
                             (if (or (fx=? cp #x2028) (fx=? cp #x2029))
                                 js? (and unicode? (fx>? cp 127))))))
                  (if escape?
                      (begin
                        (when (fx<? start i)
                          (put-string out value start (fx- i start)))
                        (case cp
                          ((34) (put-string out "\\\""))
                          ((92) (put-string out "\\\\"))
                          ((47) (put-string out "\\/"))
                          ((8) (put-string out "\\b"))
                          ((12) (put-string out "\\f"))
                          ((10) (put-string out "\\n"))
                          ((13) (put-string out "\\r"))
                          ((9) (put-string out "\\t"))
                          (else
                            (if (fx<=? cp #xffff)
                                (djn-u16 cp out)
                                (let ((n (fx- cp #x10000)))
                                  (djn-u16 (fx+ #xd800 (fxsra n 10)) out)
                                  (djn-u16 (fx+ #xdc00 (fxand n #x3ff)) out)))))
                        (loop (fx+ i 1) (fx+ i 1)))
                      (loop (fx+ i 1) start)))))
          (put-char out #\"))))))

(define (djn-flags options defaults)
  ;; Only the three boolean escaping switches may differ. Unknown/custom
  ;; options (including callbacks) fall back BEFORE any output. Identity checks
  ;; intentionally prefer a conservative fallback over calling user equality.
  (and (pmap? options)
       (fx=? (pmap-cnt options) (pmap-cnt defaults))
       (pmap-fold-fwd options
         (lambda (k v ok)
           (and ok
                (if (memq k djn-escape-keys)
                    (boolean? v)
                    (eq? v (pmap-fast-get defaults k pmap-absent))))) #t)
       (list->vector
         (map (lambda (k) (pmap-fast-get options k pmap-absent))
              djn-escape-keys))))

(define (djn-write! value writer options stock)
  ;; Stock vector ABI 1, captured in json.clj immediately after registrations:
  ;; dispatch/null/plain/double/bignum/named/string/map/array/default-options/tag.
  ;; Check before reading ANY positional field or emitting output. A stale
  ;; resource/library pairing must fail closed, not select the wrong function.
  (unless (and (pvec? stock) (fx=? (pvec-count stock) 11)
               (eq? (pvec-nth! stock 10)
                    (keyword "clojure.data.json" "native-writer-v1")))
    (jolt-throw (jolt-ex-info "data.json native writer ABI mismatch"
                  (jolt-hash-map (keyword #f "type")
                    (keyword "clojure.data.json" "native-writer-abi-mismatch")))))
  (let* ((dispatcher (pvec-nth! stock 0))
         (defaults (pvec-nth! stock 9))
         (flags (djn-flags options defaults)))
    (if (not (and (string-writer? writer) flags))
        (jolt-invoke3 (var-cell-root djn-method-cell) value writer options)
        (let ((null-writer (pvec-nth! stock 1))
              (plain-writer (pvec-nth! stock 2))
              (double-writer (pvec-nth! stock 3))
              (bignum-writer (pvec-nth! stock 4))
              (named-writer (pvec-nth! stock 5))
              (string-writer (pvec-nth! stock 6))
              (map-writer (pvec-nth! stock 7))
              (array-writer (pvec-nth! stock 8))
              (key-fn (pmap-fast-get options djn-key-fn pmap-absent))
              (value-fn (pmap-fast-get options djn-value-fn pmap-absent)))
          (letrec ((emit
            (lambda (x)
              ;; A concurrent extension after this resolution affects the next
              ;; dispatch, not the value whose method was already selected.
              ;; No user callback runs between selecting a scalar and emitting it.
              (let ((root (var-cell-root djn-method-cell)))
                (if (not (eq? root dispatcher))
                    (jolt-invoke3 root x writer options)
                    (let ((impl (protocol-resolve
                                  "clojure.data.json/JSONWriter" "-write" x)))
                      (cond
                        ((and (string? x) (eq? impl string-writer))
                         (djn-string x writer flags))
                        ((and (jolt-nil? x) (eq? impl null-writer))
                         (sb-append! writer "null"))
                        ((and (boolean? x) (eq? impl plain-writer))
                         (sb-append! writer (if x "true" "false")))
                        ((and (integer? x) (exact? x)
                              (or (eq? impl plain-writer) (eq? impl bignum-writer)))
                         (sb-append! writer (number->string x)))
                        ((and (flonum? x) (finite? x) (eq? impl double-writer))
                         (sb-append! writer (jolt-flonum->string x)))
                        ((and (or (keyword? x) (symbol-t? x))
                              (eq? impl named-writer))
                         (djn-string
                           (if (keyword? x) (keyword-t-name x) (symbol-t-name x))
                           writer flags))
                        ((and (pmap? x) (eq? impl map-writer))
                         (sb-append! writer "{")
                         (pmap-fold-seq-order x
                           (lambda (key child printed?)
                             ;; The stock key function also handles numbers,
                             ;; custom Named values, and the exact nil-key error.
                             (let ((name (if (string? key) key
                                             (jolt-invoke1 key-fn key))))
                               (unless (string? name)
                                 (jolt-throw (host-new "Exception"
                                   "JSON object keys must be strings")))
                               ;; Even the default value-fn is an omission
                               ;; sentinel if that very function is the value.
                               (if (jolt=2 value-fn child) printed?
                                   (begin
                                     (when printed? (sb-append! writer ","))
                                     (djn-string name writer flags)
                                     (sb-append! writer ":")
                                     (emit child)
                                     #t)))) #f)
                         (sb-append! writer "}"))
                        ((and (pvec? x) (eq? impl array-writer))
                         (sb-append! writer "[")
                         (let ((n (pvec-count x)))
                           (do ((i 0 (fx+ i 1))) ((fx=? i n))
                             (unless (fx=? i 0) (sb-append! writer ","))
                             (emit (pvec-nth! x i))))
                         (sb-append! writer "]"))
                        ;; Lazy seqs deliberately stay entirely portable:
                        ;; stock write-array realizes next before writing first.
                        ;; Dates, ratios, decimals, sets, records, etc. also keep
                        ;; their actual registered implementation and options.
                        (else (jolt-invoke3 impl x writer options)))))))))
            (emit value))))
    jolt-nil))

djn-write!

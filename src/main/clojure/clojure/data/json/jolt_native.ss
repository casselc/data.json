;; Library-owned, opt-in Jolt encoder. Default calls have no key cache; optional
;; payload closures cache keys only. No copied collection representation.
;; Every JSON value checks the current protocol Var
; root and selects its live method through a core-owned resolution site.
;; The caller supplies a real StringWriter. Both native output and custom
;; writers mutate that same sink, so fallback never restarts or repeats effects.

(define djn-method-cell (jolt-var "clojure.data.json" "-write"))
(define djn-json-writer-site
  (make-protocol-method-site "clojure.data.json/JSONWriter" "-write"))
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

(define (djn-with-string-output flags write)
  ;; Slot 3 belongs to this djn-write! call, not the library or a dynamic Var.
  ;; Preserve djn-string's three-argument interface; allocate lazily, then reuse
  ;; the port/extractor pair. Extraction resets the port after EACH scalar.
  (let ((scratch
          (or (vector-ref flags 3)
              (call-with-values open-string-output-port
                (lambda (out extract)
                  (let ((scratch (cons out extract)))
                    (vector-set! flags 3 scratch)
                    scratch))))))
    (write (car scratch))
    ((cdr scratch))))

(define (djn-string value writer flags)
  ;; Scan each ordinary run once. Clean immutable strings can be retained by
  ;; the actual StringWriter without copying/extracting a scratch scalar.
  ;; Both paths finish the scalar before any later custom callback can run.
  (let ((size (string-length value))
        (unicode? (vector-ref flags 0))
        (slash? (vector-ref flags 1))
        (js? (vector-ref flags 2)))
    (letrec ((next-escape
               (lambda (start)
                 (let scan ((i start))
                   (if (fx=? i size) size
                       (let ((cp (char->integer (string-ref value i))))
                         (if (or (fx<? cp 32) (fx=? cp 34) (fx=? cp 92)
                                 (and slash? (fx=? cp 47))
                                 ;; JS separators have their own switch even
                                 ;; when general Unicode escaping is enabled.
                                 (if (or (fx=? cp #x2028) (fx=? cp #x2029))
                                     js? (and unicode? (fx>? cp 127))))
                             i (scan (fx+ i 1)))))))))
      (let ((first (next-escape 0)))
        (if (fx=? first size)
            (begin
              (sb-append! writer "\"")
              (sb-append! writer value)
              (sb-append! writer "\""))
            (sb-append! writer
              (djn-with-string-output flags
                (lambda (out)
                  (put-char out #\")
                  ;; FIRST is already known; never rescan the clean prefix.
                  (let loop ((i first) (start 0))
                    (when (fx<? start i)
                      (put-string out value start (fx- i start)))
                    (unless (fx=? i size)
                      (let ((cp (char->integer (string-ref value i))))
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
                        (let ((next (fx+ i 1)))
                          (loop (next-escape next) next)))))
                  (put-char out #\")))))))))

(define (djn-checked-flags options defaults)
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
         (append (map (lambda (k) (pmap-fast-get options k pmap-absent))
                      djn-escape-keys)
                 '(#f)))))

(define (djn-flags options defaults)
  (or (and (pmap? options) (eq? options defaults)
           ;; Public write-str without options passes this captured immutable
           ;; map. Read its actual flags; never cache the call-local scratch.
           (let ((unicode? (pmap-fast-get options (car djn-escape-keys) pmap-absent))
                 (slash? (pmap-fast-get options (cadr djn-escape-keys) pmap-absent))
                 (js? (pmap-fast-get options (caddr djn-escape-keys) pmap-absent)))
             (and (boolean? unicode?) (boolean? slash?) (boolean? js?)
                  (vector unicode? slash? js? #f))))
      ;; Preserve the original guard even for malformed manually supplied
      ;; defaults: a missing escape key formerly produced an absent flag, not
      ;; rejection. Only valid identity inputs take the shortcut above.
      (djn-checked-flags options defaults)))

(define (djn-key-string name writer flags cache)
  ;; Experimental caller-owned key-only cache. Never cache values, protocol
  ;; resolution, key-fn results, or omission decisions. Escape flags form part
  ;; of each entry; key-fn has already executed before reaching this function.
  (if (not cache)
      (djn-string name writer flags)
      (let* ((table (vector-ref cache 0))
             ;; Index only the representative already retained by TABLE.
             ;; Equal fresh strings do not add aliases or retention; they
             ;; still consult the authoritative content-keyed table.
             (identities (vector-ref cache 2))
             (entry (or (hashtable-ref identities name #f)
                        (hashtable-ref table name #f))))
        (if (and entry
                 (eq? (vector-ref entry 0) (vector-ref flags 0))
                 (eq? (vector-ref entry 1) (vector-ref flags 1))
                 (eq? (vector-ref entry 2) (vector-ref flags 2)))
            (sb-append! writer (vector-ref entry 3))
            (if (and (fx<=? (string-length name) 128)
                     (fx<? (hashtable-size table) 128)
                     (fx<? (vector-ref cache 1) 8192))
                (let ((sink (host-new "StringWriter")))
                  (djn-string name sink flags)
                  (let* ((encoded (sb-str sink))
                         (size (fx+ (string-length name) (string-length encoded))))
                    (when (fx<=? (fx+ size (vector-ref cache 1)) 8192)
                      (let* ((representative (if entry (vector-ref entry 4) name))
                             (fresh (vector (vector-ref flags 0) (vector-ref flags 1)
                                            (vector-ref flags 2) encoded representative)))
                        ;; Update both indices on flag changes, including
                        ;; nested synchronous calls that reuse this cache.
                        (hashtable-set! table representative fresh)
                        (hashtable-set! identities representative fresh))
                      (vector-set! cache 1 (fx+ size (vector-ref cache 1))))
                    (sb-append! writer encoded)))
                (djn-string name writer flags))))))

(define (djn-write! value writer options stock . key-caches)
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
         (flags (djn-flags options defaults))
         (key-cache (and (pair? key-caches) (car key-caches))))
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
                    (let ((impl (djn-json-writer-site x)))
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
                                     (djn-key-string name writer flags key-cache)
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
            (dynamic-wind
              ;; Match Jolt finally semantics: a fiber park is not an exit.
              ;; Raw host winders close scratch during callback yield/resume.
              jolt-finally-in
              (lambda () (emit value))
              (lambda ()
                (let ((scratch (vector-ref flags 3)))
                  (when scratch (close-port (car scratch)))))))))
    jolt-nil))

(define (djn-make-key-cache-writer)
  ;; Source-only diagnostic factory; one closure per payload. No global cache
  ;; and no installation into data.json. Caller discards the closure on exit.
  (let ((cache (vector (make-hashtable string-hash string=?) 0
                       (make-eq-hashtable))))
    (lambda (value writer options stock)
      (djn-write! value writer options stock cache))))

djn-write!

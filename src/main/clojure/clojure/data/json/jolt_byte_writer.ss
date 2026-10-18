;; Opt-in byte writer: one serial payload owns the emitter and bounded caches.
;; Per-row operations borrow scratch and establish a restorable context; helper
;; closures are constructed once, not once for every row. Default options only.
(define djn-stock-sorted-map-folder
  (and (top-level-bound? 'stock-sorted-map?)
       (top-level-bound? 'sorted-map-fold-seq-order)
       (let ((admit (top-level-value 'stock-sorted-map?))
             (fold (top-level-value 'sorted-map-fold-seq-order)))
         (and (procedure? admit) (procedure? fold)
              (bitwise-bit-set? (procedure-arity-mask admit) 1)
              (bitwise-bit-set? (procedure-arity-mask fold) 3)
              (vector admit fold)))))
(define (djn-make-byte-buffer-writer)
  (unless (bitwise-bit-set? (procedure-arity-mask make-protocol-method-site) 3)
    (error 'byte-writer "runtime lacks observable classification boundary"))
  (let ((spare (vector (make-bytevector 65536) (make-bytevector 21)))
        (strings (make-hashtable string-hash string=?)) (cached-bytes 0)
        (abi-tag (keyword "clojure.data.json" "native-writer-v1"))
        (qualified-defaults #f)
        (writer #f) (options #f) (stock #f) (buf #f) (scratch #f)
        ;; One factory-owned box avoids the measured allocation increase from
        ;; mutable bindings; nested calls repopulate on restoration. The last
        ;; two slots hold functions from this row's immutable option map, not
        ;; cached Var roots or decisions about any receiver value.
        (stock-slots (make-vector 11 #f))
        (used 0) (flush-epoch 0)
        (batch #f) (row-start 0) (exposed? #f) (ever-exposed? #f)
        (row-value #f) (row-ending "") (row-previous #f) (row-loan #f)
        (row-complete? #f) (sink-reuse-proof (vector #f)))
    (define (sink-reusable?) (vector-ref sink-reuse-proof 0))
    ;; Stock is immutable, but protocol implementations are NOT. Decode the
    ;; row's capability once while retaining live root/site checks per value.
    ;; Reentry restores the outer capability; idle factories retain none.
    (define (set-stock! value)
      (set! stock value)
      (vector-set! stock-slots 0 (and value (pvec-nth! value 0)))
      (vector-set! stock-slots 1 (and value (pvec-nth! value 1)))
      (vector-set! stock-slots 2 (and value (pvec-nth! value 2)))
      (vector-set! stock-slots 3 (and value (pvec-nth! value 3)))
      (vector-set! stock-slots 4 (and value (pvec-nth! value 4)))
      (vector-set! stock-slots 5 (and value (pvec-nth! value 5)))
      (vector-set! stock-slots 6 (and value (pvec-nth! value 6)))
      (vector-set! stock-slots 7 (and value (pvec-nth! value 7)))
      (vector-set! stock-slots 8 (and value (pvec-nth! value 8)))
      (vector-set! stock-slots 9
                   (and value (pmap-fast-get (pvec-nth! value 9) djn-value-fn pmap-absent)))
      (vector-set! stock-slots 10
                   (and value (pmap-fast-get (pvec-nth! value 9) djn-key-fn pmap-absent))))
    (define site
      (make-protocol-method-site "clojure.data.json/JSONWriter" "-write"
        (lambda () (when writer (flush!)))))
  (define (flush-buffer!)
    (unless (fx=? used 0)
      (if batch
          (djn-byte-batch-append! batch buf 0 used)
      (let ((bytes (make-bytevector used)))
        (bytevector-copy! buf 0 bytes 0 used)
        (let ((text (utf8->string bytes)))
          ;; Existing runtime-owned setter: install a complete first
          ;; chunk as the materialized base, avoiding a second copy on
          ;; the caller's subsequent toString. Nonempty sinks append.
          (djn-sb-install-or-append! writer text))))
      (set! used 0))
    (set! flush-epoch (+ flush-epoch 1)))
  (define (flush!)
    (flush-buffer!)
    (when batch
      ;; Publish only this row, never the preceding rows in the collector.
      (sb-set! writer (djn-byte-batch-text batch row-start))
      (set! exposed? #t) (set! ever-exposed? #t)))
  (define (sync!)
    (when (and batch exposed?)
      ;; User code may modify or clear the real StringWriter, not just append.
      (djn-byte-batch-replace-row! batch row-start (djn-sb-str writer))
      (set! exposed? #f)))
  (define (append-fallback! target call-batch)
    (when call-batch
      (let ((bytes (string->utf8 (djn-sb-str target))))
        (djn-byte-batch-append! call-batch bytes 0 (bytevector-length bytes)))))
  (define (byte! b)
    (when (fx=? used 65536) (flush-buffer!))
    ;; Private 64KiB scratch, used0..65536; flush resets used before a store.
    ;; Callers emit ASCII literals/digits/escaped characters only. Never apply
    ;; this unchecked store to arbitrary public byte arrays or callback values.
    (#3%bytevector-u8-set! buf used b)
    (set! used (fx+ used 1)))
  (define (ascii! s)
    (do ((i 0 (fx+ i 1))) ((fx=? i (string-length s)))
      (byte! (char->integer (#3%string-ref s i)))))
  (define (u16! cp)
    (ascii! "\\u")
    (do ((shift 12 (fx- shift 4))) ((fx<? shift 0))
      (let ((n (fxand (fxsra cp shift) 15)))
        (byte! (if (fx<? n 10) (fx+ 48 n) (fx+ 87 n))))))
  (define (raw-string! s)
    (byte! 34)
    ;; No pre-scan or temporary UTF-8 copy. Clean ASCII advances a local cursor
    ;; instead of calling byte!/updating its captured mutable cell per byte.
    ;; Publish the cursor before every escape and flush, retaining exactly the
    ;; same scratch-boundary, budget and surrogate behavior as the scalar path.
    (let ((n (string-length s)))
      (let loop ((i 0) (cursor used))
        (cond
          ((fx=? i n) (set! used cursor))
          ((fx=? cursor 65536)
           (set! used cursor) (flush-buffer!) (loop i used))
          (else
           (let ((cp (char->integer (#3%string-ref s i))))
             (if (and (fx>=? cp 32) (fx<=? cp 127)
                      (not (fx=? cp 34)) (not (fx=? cp 92)) (not (fx=? cp 47)))
                 (begin
                   (#3%bytevector-u8-set! buf cursor cp)
                   (loop (fx+ i 1) (fx+ cursor 1)))
                 (begin
                   (set! used cursor)
                   (case cp
                     ((34 92 47) (byte! 92) (byte! cp))
                     ((8) (ascii! "\\b")) ((12) (ascii! "\\f"))
                     ((10) (ascii! "\\n")) ((13) (ascii! "\\r")) ((9) (ascii! "\\t"))
                     (else
                      (if (fx<=? cp #xffff) (u16! cp)
                          (let ((x (fx- cp #x10000)))
                            (u16! (fx+ #xd800 (fxsra x 10)))
                            (u16! (fx+ #xdc00 (fxand x #x3ff)))))))
                   (loop (fx+ i 1) used))))))))
    (byte! 34))
  (define (append-range! bytes start end)
    ;; Private buffer/scratch ranges only; bytevector-copy! retains bounds checks.
    (let loop ((offset start))
      (unless (fx=? offset end)
        (when (fx=? used 65536) (flush-buffer!))
        (let ((n (fxmin (fx- end offset) (fx- 65536 used))))
          (bytevector-copy! bytes offset buf used n)
          (set! used (fx+ used n)) (loop (fx+ offset n))))))
  (define (append-bytes! bytes)
    (append-range! bytes 0 (bytevector-length bytes)))
  (define (try-small-ascii! s)
    ;; Pure, bounded speculation in PRIVATE unused scratch. Only publish used
    ;; after the whole string qualifies. A decline may overwrite unused bytes
    ;; but cannot expose a quote/prefix or change the fallback's starting point.
    ;; With 0<=used<=65536 and used+n+2<=65536, opening used, characters
    ;; used+1+i (0<=i<n) and closing used+n+1 are all <65536. Successful
    ;; publication advances used to at most65536; no loop can flush or call out.
    (let ((n (string-length s)))
      (and (fx<? n 16) (fx<=? (fx+ used (fx+ n 2)) 65536)
        (begin
          (#3%bytevector-u8-set! buf used 34)
          (let loop ((i 0) (cursor (fx+ used 1)))
            (if (fx=? i n)
                (begin (#3%bytevector-u8-set! buf cursor 34)
                       (set! used (fx+ cursor 1)) #t)
                (let ((cp (char->integer (#3%string-ref s i))))
                  (and (fx>=? cp 32) (fx<=? cp 127)
                       (not (fx=? cp 34)) (not (fx=? cp 92)) (not (fx=? cp 47))
                       (begin (#3%bytevector-u8-set! buf cursor cp)
                              (loop (fx+ i 1) (fx+ cursor 1)))))))))))
  (define (string! s)
    ;; Consult ONLY after the live stock String implementation matched.
    ;; Default escaping is fixed; never cache callbacks/custom methods.
    (unless (try-small-ascii! s)
    (let ((hit (hashtable-ref strings s #f)))
      (if hit (append-bytes! hit)
          (let ((start used) (epoch flush-epoch))
            (raw-string! s)
            (when (and (<= (string-length s) 256)
                       (= epoch flush-epoch) (< (hashtable-size strings) 128)
                       (<= (+ cached-bytes (- used start) (* 4 (string-length s))) 65536))
              (let ((bytes (make-bytevector (- used start))))
                (bytevector-copy! buf start bytes 0 (- used start))
                (hashtable-set! strings (string-copy s) bytes)
                (set! cached-bytes (+ cached-bytes (- used start) (* 4 (string-length s)))))))))))
  (define (integer! n)
    (if (not (<= -9223372036854775808 n 18446744073709551615))
        (ascii! (djn-integer-string n))
        (let ((negative? (< n 0)))
          (let digits ((x n) (i 20))
            ;; A wire bignum becomes a fixnum after one or two digits.
            ;; Keep those remaining divisions unboxed, without creating
            ;; temporary decimal strings for the wide initial value.
            (let* ((small? (fixnum? x))
                   (q (if small? (fxquotient x 10) (quotient x 10)))
                   (r (if small? (fxremainder x 10) (remainder x 10))))
              (bytevector-u8-set! scratch i (fx+ 48 (fxabs r)))
              (if (= q 0)
                  (let ((start (if negative? (fx- i 1) i)))
                    (when negative? (bytevector-u8-set! scratch start 45))
                    (append-range! scratch start 21))
                  (digits q (fx- i 1))))))))
  (define (invoke! impl x)
    (flush!)
    (let ((result (jolt-invoke3 impl x writer options)))
      (sync!) result))
  ;; The visitor uses only the restorable factory context and its arguments.
  ;; Construct it once, not once per map; nested emits retain their own fold
  ;; accumulator and reentrant rows restore the surrounding writer context.
  (define (emit-map-entry! k child printed?)
    (let ((name (if (string? k) k
                    (begin (flush!)
                      (let ((name (jolt-invoke1 (vector-ref stock-slots 10) k)))
                        (sync!) name)))))
      ;; Unknown equality representations may themselves be observable;
      ;; preserve the stock sentinel check and its flush boundary.
      (unless (or (jolt-nil? child) (string? child) (boolean? child)
                  (number? child) (keyword? child) (symbol-t? child)
                  (pvec? child) (pmap? child) (procedure? child)) (flush!))
      (unless (string? name)
        (flush!)
        (jolt-throw (host-new "Exception" "JSON object keys must be strings")))
      (let ((omit? (jolt=2 (vector-ref stock-slots 9) child)))
        (sync!)
        (if omit? printed?
            (begin (when printed? (byte! 44))
                   (string! name) (byte! 58) (emit! child) #t)))))
  ;; Use the receiver's public seq operation, never its private tree. Tagged
  ;; tables may carry custom operations, and lazy sequence callbacks can inspect
  ;; or replace the actual writer. Publish/synchronize at every such boundary.
  (define (observe thunk)
    (flush!)
    (let ((result (thunk))) (sync!) result))
  (define (emit-seq-map! x)
    (byte! 123)
    (let loop ((xs (observe (lambda () (jolt-seq x)))) (printed? #f))
      (unless (jolt-nil? (observe (lambda () (jolt-seq xs))))
        (let* ((entry (observe (lambda () (jolt-first xs))))
               (key (observe (lambda () (jolt-nth entry 0 jolt-nil))))
               (child (observe (lambda () (jolt-nth entry 1 jolt-nil))))
               (name (if (string? key) key
                         (observe (lambda ()
                           (jolt-invoke1 (vector-ref stock-slots 10) key)))))
               ;; Stock write-object realizes next after key conversion but
               ;; before validating the key or invoking the child JSONWriter.
               (next (observe (lambda () (jolt-next xs))))
               (printed-next? (emit-map-entry! name child printed?)))
          (unless (jolt-nil? (observe (lambda () (jolt-seq next))))
            (loop next printed-next?)))))
    (byte! 125))
  (define (emit-stock-sorted-map! x)
    ;; Runtime owns both admission and representation. Pure stock sequence
    ;; operations need no publication, but every child still resolves its live
    ;; writer and publishes before observable classification/custom dispatch.
    ;; Non-stock per-object operations retain emit-seq-map! unchanged.
    (byte! 123)
    ((vector-ref djn-stock-sorted-map-folder 1) x emit-map-entry! #f)
    (byte! 125))
  (define (emit! x)
    (let ((root (var-cell-root djn-method-cell)))
      (if (not (eq? root (vector-ref stock-slots 0)))
          (invoke! root x)
          (let ((impl (site x)))
            (sync!)
            (cond
              ((and (string? x) (eq? impl (vector-ref stock-slots 6))) (string! x))
              ((and (jolt-nil? x) (eq? impl (vector-ref stock-slots 1))) (ascii! "null"))
              ((and (boolean? x) (eq? impl (vector-ref stock-slots 2)))
               (ascii! (if x "true" "false")))
              ((and (integer? x) (exact? x)
                    (or (eq? impl (vector-ref stock-slots 2)) (eq? impl (vector-ref stock-slots 4))))
               (integer! x))
              ((and (flonum? x) (finite? x) (eq? impl (vector-ref stock-slots 3)))
               (ascii! (jolt-flonum->string x)))
              ((and (or (keyword? x) (symbol-t? x)) (eq? impl (vector-ref stock-slots 5)))
               (string! (if (keyword? x) (keyword-t-name x) (symbol-t-name x))))
              ((and (pvec? x) (eq? impl (vector-ref stock-slots 8)))
               (byte! 91)
               (do ((i 0 (fx+ i 1))) ((fx=? i (pvec-count x)))
                 (unless (fx=? i 0) (byte! 44)) (emit! (pvec-nth! x i)))
               (byte! 93))
              ((and (pmap? x) (eq? impl (vector-ref stock-slots 7)))
               (byte! 123)
               (pmap-fold-seq-order x emit-map-entry! #f)
               (byte! 125))
              ;; The already-selected stock map method establishes map
              ;; semantics. Inspect no user tag/equality and bypass no :ops.
              ((and (htable? x) (eq? impl (vector-ref stock-slots 7)))
               (if (and djn-stock-sorted-map-folder
                        ((vector-ref djn-stock-sorted-map-folder 0) x))
                   (emit-stock-sorted-map! x)
                   (emit-seq-map! x)))
              (else (invoke! impl x)))))))

    ;; One set of dynamic-wind closures per serial factory, not per row.
    ;; Reentrant writes save and restore these invocation fields with the
    ;; existing writer/scratch context. Idle factories retain no input row.
    (define (run-row!)
      (emit! row-value) (ascii! row-ending) (set! row-complete? #t))
    (define (flush-row!)
      (vector-set! sink-reuse-proof 0 #f)
      (sync!) (flush-buffer!)
      (when (and batch ever-exposed?)
        (sb-set! writer (djn-byte-batch-text batch row-start)))
      ;; Publish only after successful flush, before restoring the outer row.
      ;; A throwing call cannot return this proof through the bound adapter.
      (vector-set! sink-reuse-proof 0
                   (and row-complete? batch (not ever-exposed?) #t)))
    (define (restore-row!)
      (let ((previous row-previous) (loan row-loan))
        (if previous
            (begin
              (set! writer (vector-ref previous 0))
              (set! options (vector-ref previous 1))
              (set-stock! (vector-ref previous 2))
              (set! buf (vector-ref previous 3))
              (set! used (vector-ref previous 4))
              (set! flush-epoch (vector-ref previous 5))
              (set! scratch (vector-ref previous 6))
              (set! batch (vector-ref previous 7))
              (set! row-start (vector-ref previous 8))
              (set! exposed? (vector-ref previous 9))
              (set! ever-exposed? (vector-ref previous 10))
              (set! row-value (vector-ref previous 11))
              (set! row-ending (vector-ref previous 12))
              (set! row-previous (vector-ref previous 13))
              (set! row-loan (vector-ref previous 14))
              (set! row-complete? (vector-ref previous 15)))
            (begin
              (set! writer #f) (set! options #f) (set-stock! #f)
              (set! buf #f) (set! scratch #f)
              (set! batch #f) (set! row-start 0) (set! exposed? #f)
              (set! ever-exposed? #f) (set! used 0) (set! flush-epoch 0)
              (set! row-value #f) (set! row-ending "")
              (set! row-previous #f) (set! row-loan #f)
              (set! row-complete? #f)))
        (unless spare (set! spare loan))))
    (define (finish-row!)
      (dynamic-wind jolt-finally-in flush-row! restore-row!))

    (define (render-empty-string value call-options call-stock)
      ;; The companion adapter already checked empty-vector/default/public
      ;; identities, including this exact bound factory. Use THIS factory's
      ;; site so observable receiver predicates see a materialized outer prefix.
      (let ((outer writer) (borrowed? exposed?))
        (dynamic-wind jolt-finally-in
          (lambda ()
            (let ((impl (site value)))
              (when writer (sync!))
              (if (eq? impl (pvec-nth! call-stock 8)) "[]"
                  (let ((sink (host-new "StringWriter")))
                    (when writer (flush!))
                    (jolt-invoke3 impl value sink call-options)
                    (djn-sb-str sink)))))
          (lambda ()
            (when (and writer (eq? writer outer))
              (sync!)
              ;; The enclosing callback STILL owns its borrowed writer. An
              ;; inner sync must not hide writes made after this call returns.
              (when borrowed? (set! exposed? #t)))))))

    (djn-register-byte-writer-sink-proof!
    (case-lambda
     ;; Private companion accessor; ordinary four-argument calls are unchanged.
     (() render-empty-string)
     ((value call-writer call-options call-stock . endings)
      (vector-set! sink-reuse-proof 0 #f)
      (unless (or (null? endings)
                  (and (equal? (car endings) "\n")
                       (or (null? (cdr endings))
                           (and (null? (cddr endings))
                                (vector? (cadr endings))
                                (= (vector-length (cadr endings)) 4)
                                (bytevector? (vector-ref (cadr endings) 1))))))
        (error 'byte-writer "only a row newline terminator is supported"))
      (let ((ending (if (null? endings) "" (car endings)))
            (call-batch (and (pair? endings) (pair? (cdr endings)) (cadr endings))))
        (if (not (and (pvec? call-stock) (fx=? (pvec-count call-stock) 11)
                      (eq? (pvec-nth! call-stock 10) abi-tag)
                      (string-writer? call-writer)
                      (eq? call-options (pvec-nth! call-stock 9))
                      ;; Cache only immutable default-option qualification,
                      ;; never dispatch/callback decisions or mutable values.
                      (or (eq? call-options qualified-defaults)
                          (and (for-all (lambda (k)
                                          (eq? (pmap-fast-get call-options k pmap-absent) #t))
                                        djn-escape-keys)
                               (begin (set! qualified-defaults call-options) #t)))))
            (begin (djn-write! value call-writer call-options call-stock)
                   (unless (string=? ending "") (djn-sb-append! call-writer ending))
                   (append-fallback! call-writer call-batch)
                   (vector-set! sink-reuse-proof 0 #f))
            (if (djn-empty-default-vector! value call-writer call-options call-stock)
                (begin
                  ;; Preserve the earlier native empty-vector win, including
                  ;; live custom vector dispatch, before allocating row state.
                  (unless (string=? ending "") (djn-sb-append! call-writer ending))
                  (append-fallback! call-writer call-batch)
                  (vector-set! sink-reuse-proof 0 #f)
                  jolt-nil)
            (let ((previous (and writer
                                 (vector writer options stock buf used flush-epoch scratch
                                         batch row-start exposed? ever-exposed?
                                         row-value row-ending row-previous row-loan row-complete?)))
                  (loan (or spare
                            (vector (make-bytevector 65536) (make-bytevector 21)))))
              (set! spare #f)
              (set! writer call-writer) (set! options call-options) (set-stock! call-stock)
              (set! buf (vector-ref loan 0)) (set! scratch (vector-ref loan 1))
              (set! used 0) (set! flush-epoch 0)
              (set! batch call-batch)
              (set! row-start (if batch (djn-byte-batch-size batch) 0))
              (set! exposed? #f) (set! ever-exposed? #f)
              (set! row-value value) (set! row-ending ending)
              (set! row-previous previous) (set! row-loan loan)
              (set! row-complete? #f)
              (dynamic-wind jolt-finally-in run-row! finish-row!)
              jolt-nil)))))) sink-reusable?)))
djn-make-byte-buffer-writer

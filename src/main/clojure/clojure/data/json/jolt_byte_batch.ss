;; Caller-owned collector storage. Internal prototype, not a JSON/WAL format.
;; Rows can be replaced after observable custom-writer mutation; stock rows
;; append directly and materialize immutable text only once at batch completion.
(define (djn-make-byte-batch)
  (vector (make-bytevector 65536) 0))

(define (djn-byte-batch-append! batch bytes offset count)
  (let* ((used (vector-ref batch 1))
         (needed (fx+ used count))
         (old (vector-ref batch 0)))
    (when (fx>? needed (bytevector-length old))
      (let* ((capacity (let grow ((n (bytevector-length old)))
                         (if (fx>=? n needed) n (grow (fx* n 2)))))
             (next (make-bytevector capacity)))
        (bytevector-copy! old 0 next 0 used)
        (vector-set! batch 0 next)))
    (bytevector-copy! bytes offset (vector-ref batch 0) used count)
    (vector-set! batch 1 needed)))

(define (djn-byte-batch-text batch start)
  (let* ((n (fx- (vector-ref batch 1) start))
         (bytes (make-bytevector n)))
    (bytevector-copy! (vector-ref batch 0) start bytes 0 n)
    (utf8->string bytes)))

(define (djn-byte-batch-replace-row! batch start text)
  (let ((bytes (string->utf8 text)))
    (vector-set! batch 1 start)
    (djn-byte-batch-append! batch bytes 0 (bytevector-length bytes))))

(define (djn-byte-batch-size batch) (vector-ref batch 1))

;; Preserve the original seq/first/next demand loop, including lazy inputs.
;; Do not reuse row writers: custom code may retain
;; their identity and completed row-local output. Resolve live default options
;; for each row, just as the original Clojure loop does.
(define (djn-byte-batch-write-rows! rows write stock batch max-bytes overflow)
  (let ((options-cell (jolt-var "clojure.data.json" "default-write-options")))
    (let loop ((remaining (jolt-seq rows)))
      (unless (jolt-nil? (jolt-seq remaining))
        (write (jolt-first remaining) (host-new "StringWriter")
               (var-cell-deref options-cell) stock "\n" batch)
        (when (> (djn-byte-batch-size batch) max-bytes)
          (jolt-invoke0 overflow))
        (loop (jolt-next remaining)))))
  (djn-byte-batch-text batch 0))

;; Keep these operations opaque at the Clojure edge, rather than exporting the
;; vector representation. Borrowed inputs are copied; no caller bytes retained.
(jolt-vector djn-make-byte-batch djn-byte-batch-append!
             djn-byte-batch-text djn-byte-batch-replace-row! djn-byte-batch-size)

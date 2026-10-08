;; Caller-owned collector storage. Internal prototype, not a JSON/WAL format.
;; Rows can be replaced after observable custom-writer mutation; stock rows
;; append directly and materialize immutable text only once at batch completion.
(define (djn-make-byte-batch)
  ;; Reverse full-chunk list, owned mutable tail, tail used, total used.
  ;; No grown backing ever needs to copy the preceding complete payload.
  (vector '() (make-bytevector 65536) 0 0))

(define (djn-byte-batch-append! batch bytes offset count)
  (let loop ((offset offset) (remaining count))
    (unless (fx=? remaining 0)
      (when (fx=? (vector-ref batch 2) 65536)
        (vector-set! batch 0 (cons (vector-ref batch 1) (vector-ref batch 0)))
        (vector-set! batch 1 (make-bytevector 65536))
        (vector-set! batch 2 0))
      (let* ((used (vector-ref batch 2))
             (n (fxmin remaining (fx- 65536 used))))
        (bytevector-copy! bytes offset (vector-ref batch 1) used n)
        (vector-set! batch 2 (fx+ used n))
        (vector-set! batch 3 (fx+ (vector-ref batch 3) n))
        (loop (fx+ offset n) (fx- remaining n))))))

(define (djn-byte-batch-copy-bytes batch start)
  (let* ((end (vector-ref batch 3))
         (n (fx- end start))
         (bytes (make-bytevector n)))
    (define (copy-part! part used position)
      (let ((lo (fxmax start position))
            (hi (fxmin end (fx+ position used))))
        (when (fx>? hi lo)
          (bytevector-copy! part (fx- lo position) bytes (fx- lo start) (fx- hi lo)))))
    (let loop ((parts (reverse (vector-ref batch 0))) (position 0))
      (if (null? parts)
          (copy-part! (vector-ref batch 1) (vector-ref batch 2) position)
          (begin
            (copy-part! (car parts) 65536 position)
            (loop (cdr parts) (fx+ position 65536)))))
    bytes))

(define (djn-byte-batch-text batch start)
  (utf8->string (djn-byte-batch-copy-bytes batch start)))

(define (djn-byte-batch-owned-bytes batch start)
  ;; Adopt only this fresh flattened copy, never an internal mutable chunk.
  (na-owned-bv->bytearray (djn-byte-batch-copy-bytes batch start)))

(define (djn-byte-batch-truncate! batch start)
  ;; A custom writer can shrink its current row. Prefix chunks stay unchanged;
  ;; the last retained chunk becomes the tail, and discarded chunks are freed
  ;; by GC. No byte arrays escape: all observers receive independent Strings.
  (let loop ((parts (vector-ref batch 0))
             (tail (vector-ref batch 1))
             (prefix (fx- (vector-ref batch 3) (vector-ref batch 2))))
    (if (fx>=? start prefix)
        (begin
          (vector-set! batch 0 parts)
          (vector-set! batch 1 tail)
          (vector-set! batch 2 (fx- start prefix))
          (vector-set! batch 3 start))
        (loop (cdr parts) (car parts) (fx- prefix 65536)))))

(define (djn-byte-batch-replace-row! batch start text)
  (let ((bytes (string->utf8 text)))
    (djn-byte-batch-truncate! batch start)
    (djn-byte-batch-append! batch bytes 0 (bytevector-length bytes))))

(define (djn-byte-batch-size batch) (vector-ref batch 3))

(define (djn-byte-batch-prefix! batch text)
  (let ((bytes (string->utf8 text)))
    (djn-byte-batch-append! batch bytes 0 (bytevector-length bytes))))

;; Keep these operations opaque at the Clojure edge, rather than exporting the
;; vector representation. Borrowed inputs are copied; no caller bytes retained.
(jolt-vector djn-make-byte-batch djn-byte-batch-append!
             djn-byte-batch-text djn-byte-batch-replace-row! djn-byte-batch-size)
